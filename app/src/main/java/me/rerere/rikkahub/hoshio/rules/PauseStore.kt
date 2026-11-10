package me.rerere.rikkahub.hoshio.rules

import android.content.Context
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.hoshio.together.JsonListStore

/**
 * 「拒绝的权利」（参考 Orbis）：他可以暂停这段对话或整个助手（1 分钟～24 小时），到时间自动恢复。
 * 开放申诉时，她可以写一段话申请继续，由他决定；同一次暂停最多申请 3 次。
 */
@Serializable
data class ChatPause(
    /** conversation | assistant */
    val scope: String,
    val conversationId: String? = null,
    val until: Long,
    val reason: String,
    val allowAppeal: Boolean = true,
    val appeals: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

object PauseStore : JsonListStore<ChatPause>("hoshio_pauses", ChatPause.serializer(), limit = 20) {
    const val MAX_APPEALS = 3

    /** 这段对话现在是否被暂停（过期的会顺便清掉） */
    fun activeFor(context: Context, conversationId: String?): ChatPause? {
        val now = System.currentTimeMillis()
        val list = load(context)
        if (list.any { it.until <= now }) save(context, list.filter { it.until > now })
        return list.firstOrNull { it.until > now && (it.scope == "assistant" || it.conversationId == conversationId) }
    }

    fun pause(context: Context, scope: String, conversationId: String?, minutes: Int, reason: String, allowAppeal: Boolean): ChatPause {
        val pause = ChatPause(
            scope = if (scope == "assistant") "assistant" else "conversation",
            conversationId = conversationId,
            until = System.currentTimeMillis() + minutes.coerceIn(1, 1440) * 60_000L,
            reason = reason.take(200),
            allowAppeal = allowAppeal,
        )
        update(context) { list -> list.filterNot { it.scope == pause.scope && it.conversationId == pause.conversationId } + pause }
        return pause
    }

    fun resume(context: Context, conversationId: String?) = update(context) { list ->
        list.filterNot { it.scope == "assistant" || it.conversationId == conversationId }
    }

    fun countAppeal(context: Context, pause: ChatPause) = update(context) { list ->
        list.map { if (it.createdAt == pause.createdAt) it.copy(appeals = it.appeals + 1) else it }
    }
}
