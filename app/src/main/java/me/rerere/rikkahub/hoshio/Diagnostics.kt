package me.rerere.rikkahub.hoshio

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import me.rerere.ai.provider.ModelAbility
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.hoshio.care.CarePrefs
import me.rerere.rikkahub.hoshio.life.WakeLogStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 最近的故障紀錄（像 Orbis 的 DiagnosticJournal）：聊天出錯、主動關心失敗等，只存在手機裡 */
@Serializable
data class DiagnosticEntry(val at: Long, val where: String, val message: String)

object Diagnostics {
    private const val PREFS = "hoshio_diagnostics"
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun record(context: Context, where: String, message: String) {
        runCatching {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val list = recent(context)
            val entry = DiagnosticEntry(System.currentTimeMillis(), where.take(40), message.take(300))
            // 同樣的錯誤一分鐘內只記一次
            if (list.firstOrNull()?.let { it.message == entry.message && entry.at - it.at < 60_000 } == true) return
            p.edit { putString("list", json.encodeToString((listOf(entry) + list).take(20))) }
        }
    }

    fun recent(context: Context): List<DiagnosticEntry> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", null)
            ?.let { runCatching { json.decodeFromString<List<DiagnosticEntry>>(it) }.getOrNull() }.orEmpty()
}

/**
 * 星汐「完整自我檢查」（參考 Orbis 的 orbis_self_check）：
 * 權限、模型、工具、MCP、工作區、記憶、主動關心，加上最近的故障紀錄和修法。只讀手機本機，不讀聊天、不讀金鑰。
 */
object FullSelfCheck : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val mcpManager: McpManager by inject()
    private val workspaceRepository: WorkspaceRepository by inject()
    private val memoryRepository: MemoryRepository by inject()

    val FOCUS = listOf("overview", "permissions", "model", "tools", "mcp", "workspace", "care", "errors")
    private val fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm")
    private fun time(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(fmt)

    suspend fun report(context: Context, focus: String = "overview"): String = buildString {
        val all = focus == "overview"
        val settings = settingsStore.awaitLoaded()
        val assistant = settings.assistants.firstOrNull { it.id == settings.assistantId } ?: settings.assistants.firstOrNull()
        val checks = SelfCheck.run(context)
        fun ok(title: String) = checks.firstOrNull { it.title == title }?.ok ?: true

        if (all || focus == "permissions") {
            appendLine("## 權限")
            checks.forEach { appendLine("${if (it.ok) "✅" else "⚠️"} ${it.title}（用在：${it.neededFor}）" + if (!it.ok) " → 修法：${it.fix}" else "") }
        }

        if (assistant == null) {
            appendLine("⚠️ 找不到目前的助手")
            return@buildString
        }

        if (all || focus == "model") {
            appendLine("## 模型")
            val model = settings.findModelById(assistant.chatModelId, settings.chatModelId)
            if (model == null) {
                appendLine("⚠️ 沒有設定聊天模型 → 修法：助手設定 → 模型，選一個模型")
            } else {
                val provider = model.findProvider(settings.providers)
                appendLine("模型：${model.displayName.ifBlank { model.modelId }}（${model.modelId}）")
                appendLine(if (provider == null) "⚠️ 找不到這個模型的服務商 → 修法：設定 → 服務商，確認還在" else
                    "${if (provider.enabled) "✅" else "⚠️"} 服務商：${provider.name}" + if (!provider.enabled) "（已停用 → 到設定 → 服務商打開）" else "")
                val tools = ModelAbility.TOOL in model.abilities
                appendLine("${if (tools) "✅" else "⚠️"} 工具呼叫：${if (tools) "支援" else "這個模型沒標記支援工具，所有工具（MCP、生活、搜尋）都不會給他 → 修法：模型設定勾「工具」或換模型"}")
                if (model.tools.isNotEmpty()) appendLine("模型原生工具：${model.tools.joinToString("、") { it.toString() }}" +
                    if (assistant.mcpServers.isNotEmpty()) "（和其他工具一起用時偶爾會中斷回覆，看到錯誤重試即可）" else "")
            }
        }

        if (all || focus == "tools") {
            appendLine("## 本機工具組")
            val opts = assistant.localTools
            if (opts.isEmpty()) appendLine("⚠️ 一個本機工具都沒開 → 修法：助手設定 → 本機工具")
            opts.forEach { o ->
                val name = o::class.simpleName ?: o.toString()
                val need = when (o) {
                    LocalToolOption.Location -> "定位權限"
                    LocalToolOption.ScreenView, LocalToolOption.AppLock -> "無障礙服務"
                    LocalToolOption.Music -> "通知存取"
                    else -> null
                }
                if (need != null && !ok(need)) appendLine("⚠️ $name：需要「$need」但沒開，這組工具會失敗")
                else appendLine("✅ $name")
            }
        }

        if (all || focus == "mcp") {
            appendLine("## MCP")
            val servers = settings.mcpServers.filter { it.id in assistant.mcpServers }
            if (servers.isEmpty()) appendLine("（沒有給他任何 MCP 伺服器）")
            val status = mcpManager.syncingStatus.value
            servers.forEach { s ->
                val name = s.commonOptions.name.ifBlank { "MCP" }
                val toolCount = s.commonOptions.tools.count { it.enable }
                val line = when {
                    !s.commonOptions.enable -> "⚠️ $name：已停用 → 設定 → MCP 打開"
                    else -> when (val st = status[s.id] ?: McpStatus.Idle) {
                        McpStatus.Connected -> "✅ $name：已連線，$toolCount 個工具開著"
                        McpStatus.Connecting -> "⏳ $name：連線中"
                        is McpStatus.Reconnecting -> "⏳ $name：重新連線中（第 ${st.attempt}/${st.maxAttempts} 次）"
                        is McpStatus.Error -> "⚠️ $name：連不上（${st.message.take(120)}）→ 如果是舊手機的工具：確認舊手機開著、Termux 工具機在跑、主手機 Tailscale 開著；出門要用 -ts 那組"
                        else -> "· $name：還沒連線（$toolCount 個工具，用到時才會連）"
                    }
                }
                appendLine(line)
            }
        }

        if (all || focus == "workspace") {
            appendLine("## 工作區")
            val wsId = assistant.workspaceId
            if (wsId == null) appendLine("（沒有綁工作區；需要時到設定 → 擴充功能 → 工作區建立並綁給他）")
            else {
                val ws = runCatching { workspaceRepository.getById(wsId.toString()) }.getOrNull()
                appendLine(when {
                    ws == null -> "⚠️ 綁的工作區不見了 → 重新建立或改綁"
                    ws.shellStatus == "READY" -> "✅ ${ws.name}：Linux 環境可以用"
                    ws.shellStatus == "INSTALLING" -> "⏳ ${ws.name}：Linux 環境安裝中"
                    ws.shellStatus == "BROKEN" -> "⚠️ ${ws.name}：Linux 環境壞了 → 到工作區頁重新安裝"
                    else -> "· ${ws.name}：只能讀寫檔案，還沒裝 Linux 環境（不能跑指令）"
                })
            }
        }

        if (all || focus == "care") {
            appendLine("## 主動關心與記憶")
            val prefs = CarePrefs(context)
            prefs.lastResult?.let { appendLine("最近一次主動找她：$it") }
            WakeLogStore.load(context).take(5).forEach { appendLine("· ${time(it.at)} ${it.trigger} → ${it.action}" + if (it.note.isNotBlank()) "（${it.note.take(40)}）" else "") }
            val memories = runCatching { memoryRepository.getFiles(assistant.memoryId).size }.getOrDefault(0)
            appendLine("記憶檔：$memories 個")
        }

        if (all || focus == "errors") {
            appendLine("## 最近的故障")
            val errors = Diagnostics.recent(context).take(if (all) 5 else 20)
            if (errors.isEmpty()) appendLine("（最近沒有記錄到錯誤）")
            errors.forEach { appendLine("· ${time(it.at)} [${it.where}] ${it.message}" + hint(it.message)) }
        }
        appendLine()
        append("（只讀手機本機，不讀聊天內容和金鑰。沒查到的不代表正常；需要她動手的，請用簡單的話告訴她怎麼修。）")
    }

    private fun hint(message: String): String {
        val m = message.lowercase()
        return when {
            "401" in m || "unauthorized" in m || "api key" in m -> " → 金鑰無效或過期，到設定 → 服務商重填"
            "429" in m || "quota" in m || "rate" in m -> " → 額度用完或太頻繁，等一下或換模型"
            "截斷" in message || "timeout" in m || "timed out" in m -> " → 網路不穩或連線被切斷，重試即可"
            "malformed_function_call" in m || "工具呼叫格式" in message -> " → 同時開原生搜尋和其他工具時偶爾發生，重試即可"
            "safety" in m || "安全過濾" in message -> " → 被模型的安全過濾擋下，換個說法"
            else -> ""
        }
    }
}
