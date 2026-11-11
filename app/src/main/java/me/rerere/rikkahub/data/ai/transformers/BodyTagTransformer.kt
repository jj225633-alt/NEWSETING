package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.body.BodyStore
import me.rerere.rikkahub.hoshio.body.BodyTags

/**
 * 星汐: 他回覆裡的 <body .../> 標籤（情緒黑箱與身體狀態的變化）。
 * 生成中先藏起來；這一步結束時記帳，並從存檔的正文裡拿掉，她看不到。
 */
object BodyTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        messages.map { m ->
            if (m.role != MessageRole.ASSISTANT) m else m.copy(parts = m.parts.map { p ->
                if (p is UIMessagePart.Text && p.text.contains('<')) p.copy(text = BodyTags.hide(p.text)) else p
            })
        }

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        messages.map { m ->
            if (m.role != MessageRole.ASSISTANT) return@map m
            var changed = false
            val parts = m.parts.mapIndexed { i, p ->
                if (p is UIMessagePart.Text && p.text.contains('<')) {
                    val (clean, tags) = BodyTags.parse(p.text)
                    if (tags.isNotEmpty()) {
                        runCatching { BodyStore.applyTags(ctx.context, "${m.id}:$i:${p.text.hashCode()}", tags) }
                        changed = true
                        p.copy(text = clean)
                    } else p
                } else p
            }
            if (changed) m.copy(parts = parts) else m
        }
}
