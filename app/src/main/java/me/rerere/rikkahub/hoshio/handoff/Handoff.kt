package me.rerere.rikkahub.hoshio.handoff

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.hoshio.HoshioChat
import me.rerere.rikkahub.hoshio.diary.DiaryAuthor
import me.rerere.rikkahub.hoshio.diary.DiaryEntry
import me.rerere.rikkahub.hoshio.diary.DiaryStore
import me.rerere.rikkahub.hoshio.notify.QuickReply
import me.rerere.rikkahub.utils.sendNotification
import java.time.LocalDate
import kotlinx.datetime.toInstant

/** 星汐「对话交接」的设定 */
class HandoffPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hoshio_handoff", Context.MODE_PRIVATE)

    var autoEnabled: Boolean
        get() = prefs.getBoolean("auto", true)
        set(value) = prefs.edit { putBoolean("auto", value) }

    /** 上下文超过多少 token 就交接（单位：千） */
    var thresholdK: Int
        get() = prefs.getInt("threshold_k", 150)
        set(value) = prefs.edit { putInt("threshold_k", value) }

    /** 已经交接过的旧对话，避免重复 */
    var handedOffFrom: String?
        get() = prefs.getString("from", null)
        set(value) = prefs.edit { putString("from", value) }

    var lastResult: String?
        get() = prefs.getString("last_result", null)
        set(value) = prefs.edit { putString("last_result", value) }
}

/**
 * 星汐「对话交接」：对话太长时，请他写一段交接笔记（存进日记），
 * 再开一个新对话，把笔记交给新对话里的他，自然地接着聊。
 */
object Handoff {
    /** 这段对话最新一则回覆用掉的上下文（输入＋输出 token） */
    fun contextTokens(conversation: Conversation): Int =
        conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT && it.usage != null }
            ?.usage?.let { it.promptTokens + it.completionTokens } ?: 0

    /** 背景检查：超过门槛、而且 10 分钟没有新讯息才交接（不打断正在聊的时候） */
    suspend fun maybeAuto(context: Context) {
        val prefs = HandoffPrefs(context)
        if (!prefs.autoEnabled) return
        if (me.rerere.rikkahub.hoshio.together.SleepMode.isOn(context)) return
        val (conversation, _) = HoshioChat.latestConversation() ?: return
        if (prefs.handedOffFrom == conversation.id.toString()) return
        if (contextTokens(conversation) < prefs.thresholdK * 1000) return
        val lastAt = conversation.currentMessages.lastOrNull()?.createdAt
            ?.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault())
            ?.toEpochMilliseconds() ?: 0L
        if (System.currentTimeMillis() - lastAt < 10 * 60_000) return
        if (HoshioChat.isBusy(conversation)) return
        run(context)
    }

    /** 现在交接（手动或自动）。回传结果说明 */
    suspend fun run(context: Context): String {
        val prefs = HandoffPrefs(context)
        val (old, _) = HoshioChat.latestConversation() ?: return "還沒有對話".also { prefs.lastResult = it }
        if (HoshioChat.isBusy(old)) return "他正在回話，等一下再試".also { prefs.lastResult = it }
        val tokens = contextTokens(old)
        prefs.handedOffFrom = old.id.toString()

        val note = HoshioChat.ask(
            title = "對話交接",
            text = "This conversation is getting long (about ${tokens / 1000}K tokens of context), so you two will continue in a fresh conversation. " +
                "Write a handoff note for your future self, in Traditional Chinese, 300-800 characters: what is going on in her life right now, " +
                "her mood lately, things you were in the middle of talking about, promises or plans, inside jokes and how you two talk to each other. " +
                "Only facts from this conversation. Reply with ONLY the note, no greeting, no tools.",
            timeoutMinutes = 4,
        )?.text?.trim().orEmpty()
        if (note.isBlank()) {
            prefs.handedOffFrom = null
            return "他沒有寫出交接筆記，稍後再試".also { prefs.lastResult = it }
        }
        // 交接笔记存进日记，之后新对话里也翻得到
        DiaryStore.add(context, DiaryEntry(date = LocalDate.now().toString(), author = DiaryAuthor.AI, content = "【對話交接】\n$note"))

        val reply = HoshioChat.askInNewConversation(
            title = "接續上一段對話",
            text = "You and she just moved to a fresh conversation because the previous one got too long. This is your own handoff note from a moment ago:\n" +
                "$note\n\nPick up naturally where you left off - greet her briefly in character as if nothing was interrupted " +
                "(you may lightly mention you turned to a new page). Keep it short.",
            timeoutMinutes = 4,
        )
        if (reply.text.isNotBlank()) notify(context, reply)
        return "已交接（舊對話約 ${tokens / 1000}K）".also { prefs.lastResult = "$it・${java.time.LocalDateTime.now().toString().take(16).replace('T', ' ')}" }
    }

    private fun notify(context: Context, reply: HoshioChat.Reply) {
        val id = 7306
        me.rerere.rikkahub.hoshio.widget.HoshioWidget.update(context, reply.assistantName, reply.text)
        context.sendNotification(CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, id) {
            title = reply.assistantName
            content = reply.text.take(500)
            autoCancel = true
            useDefaults = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_MESSAGE
            actions = listOf(QuickReply.action(context, reply.conversationId, id))
            contentIntent = PendingIntent.getActivity(
                context,
                id,
                conversationIntent(context, reply.conversationId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
