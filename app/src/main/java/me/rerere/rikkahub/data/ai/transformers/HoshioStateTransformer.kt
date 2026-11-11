package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.hoshio.life.MoodStore
import kotlinx.datetime.toInstant

/**
 * 星汐: 在她最新一则讯息前面放一行「他现在的状态」，让他的心情和精神自然影响语气。
 * 只放在最后一则，前面的对话不变，不影响提示词快取。
 */
object HoshioStateTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (LocalToolOption.Life !in ctx.assistant.localTools) return messages
        val index = messages.indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }
        if (index < 0) return messages
        val pet = if (LocalToolOption.Play in ctx.assistant.localTools) {
            me.rerere.rikkahub.hoshio.play.PetStore.describe(ctx.context)?.let { "\nour pet: $it" }.orEmpty()
        } else {
            ""
        }
        // 星汐: 身体状态——先处理她这则讯息（关键字、她不在的时间），再附上现在的数值
        val her = messages[index]
        runCatching {
            me.rerere.rikkahub.hoshio.body.BodyStore.onHerMessage(
                ctx.context,
                her.id.toString(),
                her.toText(),
                her.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds(),
            )
        }
        val body = runCatching { me.rerere.rikkahub.hoshio.body.BodyStore.describeForHim(ctx.context) }.getOrNull()?.let { "\n$it" }.orEmpty()
        val state = UIMessage.user("<your_state>${MoodStore.describe(ctx.context)}$pet$body</your_state>").copy(isSynthetic = true)
        return messages.subList(0, index) + state + messages.subList(index, messages.size)
    }
}
