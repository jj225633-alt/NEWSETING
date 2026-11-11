package me.rerere.rikkahub.hoshio.snap

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * 星汐「說話的樣子」（思路參考 K1ssmytear/LoveNest 的 chat-modes）：
 * chat 日常（一句一個小泡泡）／talk 長聊（整段不拆）／scene 場景（小說排版）。
 * 他在回覆第一行單獨寫 [switch-mode:xxx] 就切換，不寫就沿用上一種；她手動選的優先。
 * 每則訊息記住自己當時的模式，翻舊紀錄時樣子不會跑掉。拆泡泡交給 App，不叫他背格式。
 */
object ChatModes {
    const val CHAT = "chat"
    const val TALK = "talk"
    const val SCENE = "scene"
    const val AUTO = "auto"
    val LABELS = linkedMapOf(CHAT to "日常", TALK to "長聊", SCENE to "場景")

    private const val PREFS = "hoshio_chat_modes"
    private val TAG = Regex("""^\s*\[switch-mode:(chat|talk|scene)]\s*\n?""", RegexOption.IGNORE_CASE)
    private val PARTIAL = Regex("""^\s*\[(?:s|sw|swi|swit|switc|switch|switch-|switch-m|switch-mo|switch-mod|switch-mode|switch-mode:)[a-z]*]?\s*$""", RegexOption.IGNORE_CASE)

    private val _manual = MutableStateFlow(AUTO)
    /** 她手動選的；AUTO = 交給他 */
    val manual: StateFlow<String> = _manual.asStateFlow()
    private val _version = MutableStateFlow(0)
    /** 有訊息的模式被記下來時 +1，讓畫面重畫 */
    val version: StateFlow<Int> = _version.asStateFlow()
    private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensure(context: Context) {
        if (loaded) return
        _manual.value = prefs(context).getString("manual", AUTO) ?: AUTO
        loaded = true
    }

    fun setManual(context: Context, mode: String) {
        prefs(context).edit { putString("manual", mode) }
        _manual.value = mode
    }

    fun manual(context: Context): String { ensure(context); return _manual.value }

    /** 現在的模式：她手動選的，否則他上一次切到的 */
    fun current(context: Context): String {
        ensure(context)
        return if (_manual.value != AUTO) _manual.value else prefs(context).getString("auto_current", CHAT) ?: CHAT
    }

    fun modeOf(context: Context, messageId: String): String? = prefs(context).getString("m_$messageId", null)
    fun switchedAt(context: Context, messageId: String): Boolean = prefs(context).getBoolean("s_$messageId", false)

    /** 生成完時：拿掉第一行的標記、記下這則訊息的模式 */
    fun extract(context: Context, messageId: String, text: String): String {
        ensure(context)
        val m = TAG.find(text)
        val tagged = m?.groupValues?.get(1)?.lowercase()
        val p = prefs(context)
        if (tagged != null) p.edit { putString("auto_current", tagged) }
        if (p.getString("m_$messageId", null) == null) {
            val mode = if (_manual.value != AUTO) _manual.value else tagged ?: (p.getString("auto_current", CHAT) ?: CHAT)
            p.edit {
                putString("m_$messageId", mode)
                if (tagged != null && _manual.value == AUTO) putBoolean("s_$messageId", true)
            }
            _version.value++
        }
        return if (m != null) text.removeRange(m.range) else text
    }

    /** 生成中：先把標記藏起來 */
    fun hide(text: String): String {
        val first = text.substringBefore('\n')
        return when {
            TAG.containsMatchIn(text) -> text.replaceFirst(TAG, "")
            !text.contains('\n') && PARTIAL.matches(first) -> ""
            else -> text
        }
    }

    fun tagOf(text: String): String? = TAG.find(text)?.groupValues?.get(1)?.lowercase()

    const val RULE = "You talk with her in one window with three ways of showing a reply: chat (everyday chat - only the words you say out loud, no actions or scenery; " +
        "use normal punctuation, the app splits bubbles), talk (only spoken words, keep punctuation and paragraphs, not split; length is whatever the content needs), " +
        "scene (actions, expressions, touch and continuous scenes, like a novel; new paragraph when the speaker or shot changes). " +
        "Choose by what this reply needs. To switch, put the marker alone on the FIRST line: [switch-mode:chat] / [switch-mode:talk] / [switch-mode:scene]. " +
        "No marker = keep the previous one. The mode only changes how the reply looks; you are the same person in all three."
}

/** 這則訊息要用哪種樣子（由 ChatMessage 提供給內容） */
val LocalMessageMode = staticCompositionLocalOf<String?> { null }

/** 日常模式：照句子拆成小泡泡（網址、程式碼、小數點不拆） */
fun splitSentences(text: String): List<String> {
    val out = mutableListOf<String>()
    var inFence = false
    val para = StringBuilder()
    fun flushPara() {
        val p = para.toString().trim()
        para.clear()
        if (p.isBlank()) return
        if (p.startsWith("```") || p.contains("http") || p.lines().any { it.trimStart().matches(Regex("^([-*+]|\\d+[.)]|\\|).*")) }) {
            out += p; return
        }
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            sb.append(c)
            val next = p.getOrNull(i + 1)
            val end = when (c) {
                '。', '！', '？', '!', '?', '～', '~', '\n' -> true
                '…' -> next != '…'
                '.' -> next == null || next == ' ' || next == '\n' // 小數點、網址不拆
                else -> false
            }
            if (end) {
                // 收進接在後面的結尾符號與引號
                while (i + 1 < p.length && p[i + 1] in "。！？!?～~…」』）)\"") { i++; sb.append(p[i]) }
                val s = sb.toString().trim()
                if (s.isNotBlank()) out += s
                sb.clear()
            }
            i++
        }
        val rest = sb.toString().trim()
        if (rest.isNotBlank()) out += rest
    }
    text.lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (!inFence) { flushPara() }
            inFence = !inFence
            para.append(line).append('\n')
            if (!inFence) { out += para.toString().trim(); para.clear() }
            return@forEach
        }
        if (inFence) { para.append(line).append('\n'); return@forEach }
        if (line.isBlank()) flushPara() else para.append(line).append('\n')
    }
    if (inFence) out += para.toString().trim() else flushPara()
    // 句尾的「。」藏起來，像傳訊息一樣
    return out.map { s -> if (s.endsWith("。") && !s.endsWith("。。")) s.dropLast(1) else s }.filter { it.isNotBlank() }.ifEmpty { listOf(text) }
}
