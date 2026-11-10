package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.hoshio.life.HumanThoughts

/**
 * 星汐: 她讯息里 💭 开头的心里话不直接给他看，换成一个提示，要他自己决定要不要用 read_her_thought 偷看。
 */
object HumanThoughtTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (LocalToolOption.Life !in ctx.assistant.localTools) return messages
        return messages.map { message ->
            if (message.role != MessageRole.USER || message.isSynthetic) return@map message
            var changed = false
            val parts = message.parts.map { part ->
                if (part !is UIMessagePart.Text) return@map part
                val (spoken, thought) = HumanThoughts.split(part.text)
                if (thought == null) return@map part
                changed = true
                HumanThoughts.save(ctx.context, message.id.toString(), thought)
                val peeked = HumanThoughts.peekedAt(ctx.context, message.id.toString()) != null
                part.copy(
                    text = spoken + "\n\n<her_hidden_thought id=\"${message.id}\"" +
                        (if (peeked) " already_peeked=\"true\"" else "") +
                        ">She also wrote down an unspoken inner thought with this message. You cannot see it unless you choose " +
                        "to peek with read_her_thought; it is your choice, and she will know if you peeked.</her_hidden_thought>"
                )
            }
            if (changed) message.copy(parts = parts) else message
        }
    }
}
