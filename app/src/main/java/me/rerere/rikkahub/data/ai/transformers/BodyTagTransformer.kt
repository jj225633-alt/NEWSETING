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
                if (p is UIMessagePart.Text) {
                    var t = me.rerere.rikkahub.hoshio.snap.ChatModes.hide(p.text)
                    if (t.contains('<')) t = me.rerere.rikkahub.hoshio.snap.PovSnap.hidePartial(BodyTags.hide(t))
                    if (t != p.text) p.copy(text = t) else p
                } else p
            })
        }

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        messages.map { m ->
            if (m.role != MessageRole.ASSISTANT) return@map m
            var changed = false
            val parts = m.parts.mapIndexed { i, p ->
                if (p is UIMessagePart.Text) {
                    // 星汐: 說話的樣子（第一行的 [switch-mode:xxx]）
                    val moded = runCatching { me.rerere.rikkahub.hoshio.snap.ChatModes.extract(ctx.context, m.id.toString(), p.text) }.getOrDefault(p.text)
                    val (clean, tags) = if (moded.contains('<')) BodyTags.parse(moded) else moded to emptyList()
                    if (tags.isNotEmpty()) runCatching { BodyStore.applyTags(ctx.context, "${m.id}:$i:${p.text.hashCode()}", tags) }
                    // 星汐: 他的截圖標記換成卡片記號
                    val snapped = runCatching { me.rerere.rikkahub.hoshio.snap.PovSnap.extract(ctx.context, clean) }.getOrDefault(clean)
                    if (snapped != p.text) {
                        changed = true
                        p.copy(text = snapped)
                    } else p
                } else p
            }
            if (changed) m.copy(parts = parts) else m
        }
}
