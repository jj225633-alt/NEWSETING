package me.rerere.rikkahub.hoshio.screen

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.hoshio.HoshioChat
import me.rerere.rikkahub.hoshio.notify.QuickReply
import me.rerere.rikkahub.utils.sendNotification

/**
 * 星汐「一起看螢幕」：她开始后，每隔几分钟他看一眼螢幕，像坐在旁边一样说一两句，
 * 话会浮在螢幕上方；结束时他留一段观后感。
 */
class WatchTogetherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (!startForegroundCompat("準備一起看…")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val minutes = intent.getIntExtra(EXTRA_INTERVAL, 3).coerceIn(1, 30)
                val topic = intent.getStringExtra(EXTRA_TOPIC).orEmpty()
                _state.value = WatchState(running = true, topic = topic, intervalMinutes = minutes)
                loop?.cancel()
                loop = scope.launch { watchLoop(minutes, topic) }
            }

            else -> if (!_state.value.running) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun watchLoop(minutes: Int, topic: String) {
        delay(20_000) // 给她时间切到要看的东西
        while (scope.isActive) {
            runCatching { glance(topic) }.onFailure { Log.w(TAG, "glance failed", it) }
            delay(minutes * 60_000L)
        }
    }

    private suspend fun glance(topic: String) {
        val power = getSystemService(POWER_SERVICE) as? PowerManager
        if (power?.isInteractive == false) return
        if (HoshioAccessibilityService.foregroundPackage == packageName) return // 她在星汐里，直接聊就好
        if (!HoshioAccessibilityService.isRunning) {
            updateNotification("無障礙服務沒開，他看不到螢幕")
            return
        }
        val (conversation, _) = HoshioChat.latestConversation() ?: return
        if (HoshioChat.isBusy(conversation)) return
        val count = _state.value.glances + 1
        val reply = HoshioChat.ask(
            title = "一起看螢幕",
            text = "You and her are watching her phone screen together right now" +
                (if (topic.isNotBlank()) " (she said: $topic)" else "") + ". This is glance #$count. " +
                "Call look_at_screen, then react like you are sitting right beside her watching: one or two short, casual sentences, " +
                "like a live comment (a feeling, a joke, a small observation). Do not describe the whole screen and do not ask her to reply. " +
                "If the screen is private or black, just say something light.",
            timeoutMinutes = 3,
        ) ?: return
        _state.value = _state.value.copy(glances = count, lastComment = reply.text.ifBlank { _state.value.lastComment })
        if (reply.text.isNotBlank()) {
            HoshioAccessibilityService.showBubble(reply.assistantName, reply.text)
            updateNotification(reply.text)
        }
    }

    private fun finish() {
        val state = _state.value
        if (!state.running) {
            stopForegroundAndSelf()
            return
        }
        _state.value = state.copy(running = false)
        updateNotification("他正在寫觀後感…")
        scope.launch {
            loop?.cancelAndJoin()
            val minutes = (System.currentTimeMillis() - state.startedAt) / 60_000
            if (state.glances > 0) {
                runCatching {
                    val reply = HoshioChat.ask(
                        title = "一起看結束",
                        text = "She ended 一起看螢幕" + (if (state.topic.isNotBlank()) " (${state.topic})" else "") +
                            ". You watched together for about $minutes minutes and glanced ${state.glances} times. " +
                            "Leave her a short 觀後感 in 2-4 sentences: the part that stayed with you most and how you felt watching it with her. " +
                            "Do not call look_at_screen again.",
                    )
                    if (reply != null && reply.text.isNotBlank()) notifyReply(reply)
                }.onFailure { Log.w(TAG, "summary failed", it) }
            }
            stopForegroundAndSelf()
        }
    }

    private fun notifyReply(reply: HoshioChat.Reply) {
        val id = 7305
        sendNotification(CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, id) {
            title = "${reply.assistantName}・觀後感"
            content = reply.text.take(500)
            autoCancel = true
            useDefaults = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_MESSAGE
            actions = listOf(QuickReply.action(this@WatchTogetherService, reply.conversationId, id))
            contentIntent = PendingIntent.getActivity(
                this@WatchTogetherService,
                id,
                conversationIntent(this@WatchTogetherService, reply.conversationId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _state.value = _state.value.copy(running = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_rikkahub)
        .setContentTitle("正在一起看螢幕")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .addAction(
            0,
            "結束",
            PendingIntent.getService(
                this,
                NOTIFICATION_ID,
                Intent(this, WatchTogetherService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun updateNotification(text: String) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun startForegroundCompat(text: String): Boolean {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("一起看螢幕")
                .setDescription("一起看螢幕時顯示，可以從這裡結束")
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build()
        )
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(text))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start watch together", e)
            false
        }
    }

    data class WatchState(
        val running: Boolean = false,
        val topic: String = "",
        val intervalMinutes: Int = 3,
        val startedAt: Long = System.currentTimeMillis(),
        val glances: Int = 0,
        val lastComment: String = "",
    )

    companion object {
        private const val TAG = "HoshioWatchTogether"
        private const val CHANNEL_ID = "hoshio_watch_together"
        private const val NOTIFICATION_ID = 7304
        private const val ACTION_START = "app.hoshio.watch.START"
        private const val ACTION_STOP = "app.hoshio.watch.STOP"
        private const val EXTRA_INTERVAL = "interval"
        private const val EXTRA_TOPIC = "topic"

        private val _state = MutableStateFlow(WatchState())
        val state: StateFlow<WatchState> = _state.asStateFlow()

        fun start(context: Context, intervalMinutes: Int, topic: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WatchTogetherService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_INTERVAL, intervalMinutes)
                    .putExtra(EXTRA_TOPIC, topic),
            )
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, WatchTogetherService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
