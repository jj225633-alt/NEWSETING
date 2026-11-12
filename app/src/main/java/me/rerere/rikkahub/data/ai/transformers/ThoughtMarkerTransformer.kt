package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock

/**
 * 星汐: 有些模型或中轉站會把思考寫成正文裡的 [-thought-] … [-/thought-]（舊對話裡也可能留著這種寫法，模型會跟著學）。
 * 把它們收進可以摺疊的「思考」，不要當成說出口的話顯示。
 */
object ThoughtMarkerTransformer : OutputMessageTransformer {
    private val BLOCK = Regex("""\[-?\s*thought\s*-?\]([\s\S]*?)(\[-?\s*/\s*thought\s*-?\]|$)""", RegexOption.IGNORE_CASE)

    override suspend fun visualTransform(ctx: TransformerContext, messages: List<UIMessage>) = convert(messages, finished = false)

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>) = convert(messages, finished = true)

    private fun convert(messages: List<UIMessage>, finished: Boolean): List<UIMessage> = messages.map { m ->
        if (m.role != MessageRole.ASSISTANT || m.parts.none { it is UIMessagePart.Text && BLOCK.containsMatchIn(it.text) }) return@map m
        val now = Clock.System.now()
        m.copy(parts = m.parts.flatMap { p ->
            if (p !is UIMessagePart.Text || !BLOCK.containsMatchIn(p.text)) return@flatMap listOf(p)
            val out = mutableListOf<UIMessagePart>()
            var last = 0
            BLOCK.findAll(p.text).forEach { match ->
                val before = p.text.substring(last, match.range.first).trim()
                if (before.isNotEmpty()) out += UIMessagePart.Text(before)
                val thought = match.groupValues[1].trim()
                val closed = match.groupValues[2].isNotEmpty()
                if (thought.isNotEmpty()) out += UIMessagePart.Reasoning(reasoning = thought, createdAt = now, finishedAt = if (closed || finished) now else null)
                last = match.range.last + 1
            }
            val after = p.text.substring(last.coerceAtMost(p.text.length)).trim()
            if (after.isNotEmpty()) out += UIMessagePart.Text(after)
            out
        })
    }
}
