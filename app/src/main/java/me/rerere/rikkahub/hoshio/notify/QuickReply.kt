package me.rerere.rikkahub.hoshio.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/**
 * 星汐「通知直接回复」：在他的讯息通知上直接打字回他，不用打开 App。
 */
object QuickReply {
    const val KEY_TEXT = "hoshio_quick_reply_text"
    private const val ACTION = "app.hoshio.QUICK_REPLY"
    private const val EXTRA_CONVERSATION = "conversation_id"
    private const val EXTRA_NOTIFICATION = "notification_id"

    /** 产生「回复」按钮，送出后把文字放进那段对话 */
    fun action(context: Context, conversationId: String, notificationId: Int): NotificationCompat.Action {
        val intent = Intent(context, QuickReplyReceiver::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_CONVERSATION, conversationId)
            .putExtra(EXTRA_NOTIFICATION, notificationId)
        val pending = PendingIntent.getBroadcast(
            context,
            (conversationId.hashCode() * 31) + notificationId,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val input = RemoteInput.Builder(KEY_TEXT).setLabel("回覆他…").build()
        return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "回覆", pending)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    internal fun handle(context: Context, intent: Intent, chatService: ChatService) {
        if (intent.action != ACTION) return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION)?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        if (text.isEmpty()) return
        chatService.sendMessage(conversationId, listOf(UIMessagePart.Text(text)))
        // 收起原本的通知；在背景等他回覆，回好了再发一则新的通知（也能继续直接回复）
        NotificationManagerCompat.from(context).cancel(notificationId)
        me.rerere.rikkahub.hoshio.care.CareWorker.awaitReply(context, conversationId.toString())
    }
}

class QuickReplyReceiver : BroadcastReceiver(), KoinComponent {
    private val chatService: ChatService by inject()

    override fun onReceive(context: Context, intent: Intent) {
        QuickReply.handle(context, intent, chatService)
    }
}
