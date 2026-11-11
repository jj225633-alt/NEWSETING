package me.rerere.rikkahub.hoshio.care

import android.content.Context
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.backgroundTextGenerationParams
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.hoshio.life.WakeLogStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * 醒來先私下想想（參考 Orbis 哨兵的 proactive decision）：
 * 不寫進對話、不用工具，只讓他看著最近的對話和這次醒來的原因，決定要不要開口。
 * 決定不打擾時，對話裡不留任何痕跡，只記在他的作息。
 */
object WakeDecision : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val providerManager: ProviderManager by inject()

    data class Decision(val talk: Boolean, val thought: String)

    /** 這些是她要求的或攸關安全的，一定要送到，不讓他私下跳過 */
    fun shouldDecide(key: String): Boolean =
        key == "free_time" || key == "screen_observation" || key == "bedtime" || key == "night_usage" ||
            key.startsWith("wake_rule_") || key.startsWith("place_")

    /** 失敗（沒模型、網路錯誤、看不懂）時回傳 null，呼叫端照原本的方式叫醒他 */
    suspend fun decide(context: Context, conversation: Conversation, title: String, eventText: String): Decision? = runCatching {
        val settings = settingsStore.awaitLoaded()
        val assistant = settings.assistants.firstOrNull { it.id == conversation.assistantId } ?: return null
        val model = settings.findModelById(assistant.chatModelId, settings.chatModelId) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        val nickname = settings.displaySetting.userNickname.ifBlank { "她" }
        val name = assistant.name.ifBlank { "時澈" }
        val zone = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm")

        val recent = conversation.currentMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(16)
            .mapNotNull { m ->
                val text = m.toText().replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                    .ifBlank { return@mapNotNull null }.take(400)
                val at = Instant.ofEpochMilli(m.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds())
                    .atZone(zone).format(fmt)
                val who = if (m.role == MessageRole.USER) nickname else name
                "[$at] $who：$text"
            }
        val wakes = WakeLogStore.load(context).take(5).map { r ->
            val at = Instant.ofEpochMilli(r.at).atZone(zone).format(fmt)
            val what = when (r.action) {
                "talked" -> "找了她"
                "silent", "decided_silent" -> "沒打擾"
                "tools" -> "自己做了點事"
                else -> "沒成功"
            }
            "[$at] ${r.trigger} → $what" + if (r.note.isNotBlank()) "（${r.note.take(40)}）" else ""
        }
        val body = runCatching { me.rerere.rikkahub.hoshio.body.BodyStore.describeForHim(context) }.getOrNull()
        val persona = assistant.systemPrompt.replace("{{user}}", nickname)

        val instruction = buildString {
            appendLine("（這是你心裡的念頭，不會被$nickname 看到，也不會寫進對話。）")
            appendLine("現在是 ${LocalDateTime.now().format(fmt)}。你剛剛醒來，原因是「$title」：")
            appendLine(eventText.take(1200))
            appendLine()
            if (recent.isNotEmpty()) {
                appendLine("<recent_chat>")
                recent.forEach { appendLine(it) }
                appendLine("</recent_chat>")
            }
            if (wakes.isNotEmpty()) {
                appendLine("<your_recent_wakes>")
                wakes.forEach { appendLine(it) }
                appendLine("</your_recent_wakes>")
            }
            body?.let { appendLine("<your_body>$it</your_body>") }
            appendLine()
            appendLine("以你自己的個性想一想：現在要不要去找$nickname（傳訊息、看看她、或做點什麼再告訴她）？")
            appendLine("考慮她可能在忙什麼、你們上次聊到哪裡、你剛剛是不是才找過她、這時候開口會不會打擾，以及你自己現在想不想。")
            appendLine("像真人一樣，不必每次都開口；但真的想她、有話想說、或擔心她時就去找她。")
            appendLine("只輸出一行 JSON，不要其他文字：{\"talk\": true 或 false, \"thought\": \"一句你心裡的想法（繁體中文，30 字內）\"}")
        }
        val result = providerManager.getProviderByType(provider).generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.system(persona), UIMessage.user(instruction)),
            params = backgroundTextGenerationParams(model, Uuid.random()),
        )
        val text = result.message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            .replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        parse(text)
    }.getOrNull()

    private fun parse(text: String): Decision? {
        val json = Regex("\\{[\\s\\S]*?\\}").find(text)?.value
        if (json != null) {
            runCatching {
                val obj = Json { isLenient = true }.parseToJsonElement(json) as JsonObject
                val talk = obj["talk"]?.jsonPrimitive?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: return@runCatching null
                return Decision(talk, obj["thought"]?.jsonPrimitive?.contentOrNull.orEmpty().take(80))
            }
        }
        return when {
            Regex("\"?talk\"?\\s*[:：]\\s*false").containsMatchIn(text) -> Decision(false, "")
            Regex("\"?talk\"?\\s*[:：]\\s*true").containsMatchIn(text) -> Decision(true, "")
            else -> null
        }
    }
}
