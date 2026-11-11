package me.rerere.rikkahub.hoshio.snap

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/*
 * 星汐「他視角的聊天截圖」（思路參考 K1ssmytear/LoveNest 的 pov-snap）：
 * 他在回覆裡單獨一行寫 <pov-snap match="..." context="2" note="..."/> 或 <pov-snap last="4"/>，
 * 那一行換成一張截圖卡片：他的話在右邊、她的話在左邊，顏色跟著人走。截下的內容第一次顯示時就固定存起來。
 */

@Serializable
data class SnapLine(val him: Boolean, val text: String)

@Serializable
data class PovSnapItem(
    val id: String,
    val at: Long = System.currentTimeMillis(),
    val match: String? = null,
    val context: Int = 2,
    val last: Int? = null,
    val note: String = "",
    /** 第一次顯示時解析好的內容；null = 還沒解析 */
    val lines: List<SnapLine>? = null,
    val notFound: Boolean = false,
)

/** 聊天頁提供：目前對話的訊息（拿來找要截的那幾句） */
val LocalSnapMessages = staticCompositionLocalOf<() -> List<UIMessage>> { { emptyList() } }

object PovSnap {
    private const val PREFS = "hoshio_pov_snap"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _items = MutableStateFlow<List<PovSnapItem>>(emptyList())
    val items: StateFlow<List<PovSnapItem>> = _items.asStateFlow()
    private var loaded = false

    val MARKER = Regex("⟦pov-snap:([A-Za-z0-9]+)⟧")
    private val TAG = Regex("""<pov-snap\b([^<>]*?)/?>""")
    private val ATTR = Regex("""(\w+)\s*=\s*"([^"]*)"""")
    private val PARTIAL = Regex("""<(?:p|po|pov|pov-|pov-s|pov-sn|pov-sna|pov-snap)(\b[^<>]*)?$""")

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): List<PovSnapItem> {
        if (!loaded) {
            _items.value = prefs(context).getString("items", null)?.let { runCatching { json.decodeFromString<List<PovSnapItem>>(it) }.getOrNull() }.orEmpty()
            loaded = true
        }
        return _items.value
    }

    @Synchronized
    private fun save(context: Context, list: List<PovSnapItem>) {
        val trimmed = list.take(300)
        _items.value = trimmed
        prefs(context).edit { putString("items", json.encodeToString(trimmed)) }
    }

    fun get(context: Context, id: String) = load(context).firstOrNull { it.id == id }

    fun delete(context: Context, id: String) = save(context, load(context).filterNot { it.id == id })

    /** 回覆裡的標記換成 ⟦pov-snap:id⟧，並記下要截什麼 */
    fun extract(context: Context, text: String): String {
        if (!text.contains("<pov-snap")) return text
        val created = mutableListOf<PovSnapItem>()
        val out = text.replace(TAG) { m ->
            val attrs = ATTR.findAll(m.groupValues[1]).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
            val item = PovSnapItem(
                id = Uuid.random().toString().replace("-", "").take(12),
                match = attrs["match"]?.ifBlank { null },
                context = attrs["context"]?.toIntOrNull()?.coerceIn(0, 6) ?: 2,
                last = attrs["last"]?.toIntOrNull()?.coerceIn(1, 12),
                note = attrs["note"].orEmpty().take(120),
            )
            if (item.match == null && item.last == null) "" else {
                created += item
                "\n⟦pov-snap:${item.id}⟧\n"
            }
        }
        if (created.isNotEmpty()) save(context, created + load(context))
        return out.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun hidePartial(text: String): String = text.replace(TAG, "").replace(PARTIAL, "")

    private fun clean(text: String) = text
        .replace(Regex("<think>[\\s\\S]*?</think>"), "")
        .replace(Regex("<(?:body|blackbox)\\b[^<>]*/?>"), "")
        .replace(MARKER, "")
        .lines().filterNot { it.trimStart().startsWith("💭") }.joinToString("\n")
        .trim()

    private fun lineOf(m: UIMessage): SnapLine? {
        val text = clean(m.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
        val extra = when {
            m.parts.any { it is UIMessagePart.Image } -> "［圖片］"
            m.parts.any { it is UIMessagePart.Audio } -> "［語音］"
            else -> ""
        }
        val all = listOf(text, extra).filter { it.isNotBlank() }.joinToString(" ")
        if (all.isBlank()) return null
        return SnapLine(him = m.role == MessageRole.ASSISTANT, text = all.take(600))
    }

    /** 第一次顯示時解析：從目前對話裡找出要截的那幾句，存起來（之後不會變） */
    fun resolve(context: Context, id: String, conversation: List<UIMessage>): PovSnapItem? {
        val item = get(context, id) ?: return null
        if (item.lines != null || item.notFound) return item
        val markerIndex = conversation.indexOfFirst { m -> m.parts.any { it is UIMessagePart.Text && it.text.contains("⟦pov-snap:$id⟧") } }
        if (markerIndex < 0) return item // 還在生成中，等存好再解析
        val before = conversation.subList(0, markerIndex)
            .filter { (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && !it.isNoticeOnly() }
        val picked: List<UIMessage>? = if (item.match != null) {
            val needle = item.match.replace(Regex("\\s"), "")
            val idx = before.indexOfLast { m -> clean(m.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }).replace(Regex("\\s"), "").contains(needle) }
            if (idx < 0) null else before.subList((idx - item.context).coerceAtLeast(0), (idx + item.context + 1).coerceAtMost(before.size))
        } else {
            before.takeLast(item.last ?: 4)
        }
        val lines = picked?.mapNotNull(::lineOf)?.takeLast(12)
        val resolved = if (lines.isNullOrEmpty()) item.copy(notFound = true) else item.copy(lines = lines)
        save(context, load(context).map { if (it.id == id) resolved else it })
        return resolved
    }

    private fun UIMessage.isNoticeOnly() = parts.isNotEmpty() && parts.all { it is UIMessagePart.Notice }

    /** 每輪給他的一段說明 */
    const val RULE = "You may take a screenshot of your chat from YOUR point of view (your lines on the right, hers on the left) when YOU want to keep a moment " +
        "(something said for the first time, making up after a fight, an inside joke, a line that changed something). Put it on its own line: " +
        "<pov-snap match=\"a few words from the MIDDLE line of the part you want\" context=\"2\" note=\"why you want to keep it\"/> or <pov-snap last=\"4\"/>. " +
        "3-8 lines, max 12. Do not announce it or ask; it is rare and only because you want to remember."
}
