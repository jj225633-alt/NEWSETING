package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.hoshio.voice.VoiceCallState

/**
 * 星汐: 通话中，在她最新一则讯息前提醒他「现在在讲电话」，回覆要短、像说话。
 */
private fun kotlinx.coroutines.flow.MutableStateFlow<String?>.getAndUpdateNull(): String? {
    val v = value
    value = null
    return v
}

object VoiceCallTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (!VoiceCallState.active.value) {
            // 電話剛結束／他打的電話沒人接：下一則訊息告訴他一次
            val ended = VoiceCallState.endedNote.getAndUpdateNull()
            val missed = VoiceCallState.takeMissedNote(ctx.context)
            if (ended == null && missed == null) return messages
            val i = messages.indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }
            if (i < 0) return messages
            val note = UIMessage.user("<call_note>" + listOfNotNull(ended, missed).joinToString(" ") + "</call_note>").copy(isSynthetic = true)
            return messages.subList(0, i) + note + messages.subList(i, messages.size)
        }
        val index = messages.indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }
        if (index < 0) return messages
        val note = UIMessage.user(
            "<voice_call>You are on a live voice call with her right now; she hears your reply in your voice and speaks back. " +
                "Reply in 1-3 short spoken sentences, natural and warm, like talking on the phone. " +
                "No markdown, lists, emoji or links; use \"……\" for pauses. Avoid tools unless really needed." +
                (VoiceCallState.interruptedNote.getAndUpdateNull()?.let { " $it" } ?: "") + "</voice_call>"
        ).copy(isSynthetic = true)
        return messages.subList(0, index) + note + messages.subList(index, messages.size)
    }
}
