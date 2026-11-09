package me.rerere.rikkahub.hoshio.care

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "HoshioCare"
private const val PERIODIC_WORK = "hoshio_care_periodic"
private const val TEST_WORK = "hoshio_care_test"
private const val KEY_TEST = "test"
private const val CARE_NOTIFICATION_ID = 7301

/** 一次要让 AI 知道的手机事件 */
private data class CareEvent(val key: String, val title: String, val text: String)

/**
 * 星汐「主动关心」：每 15 分钟检查一次手机状态，有需要关心的事就以通知（Notice）
 * 的形式放进该助手最近的对话，让 AI 主动回一句话，再用系统通知显示出来。
 */
class CareWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val chatService: ChatService by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val settingsStore: SettingsStore by inject()

    override suspend fun doWork(): Result {
        val prefs = CarePrefs(applicationContext)
        val isTest = inputData.getBoolean(KEY_TEST, false)
        val events = if (isTest) {
            listOf(
                CareEvent(
                    key = "test",
                    title = "主動關心測試",
                    text = "The user just tapped \"test proactive care\" in the app settings to check that this feature works. " +
                        "Greet them briefly and let them know you will reach out like this when something comes up " +
                        "(for example low battery or using the phone late at night).",
                )
            )
        } else {
            if (!prefs.anyEnabled) return Result.success()
            detectEvents(prefs)
        }
        for (event in events) {
            runCatching { dispatch(prefs, event) }
                .onFailure {
                    Log.e(TAG, "dispatch ${event.key} failed", it)
                    prefs.lastResult = "失敗：${it.message ?: it.javaClass.simpleName}"
                }
        }
        return Result.success()
    }

    private fun detectEvents(prefs: CarePrefs): List<CareEvent> {
        val events = mutableListOf<CareEvent>()
        val now = LocalDateTime.now()

        if (prefs.lowBatteryEnabled) {
            val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            when {
                percent < 0 -> Unit
                plugged || percent >= CarePrefs.LOW_BATTERY_RESET_PERCENT -> prefs.lowBatteryAlerted = false
                percent <= CarePrefs.LOW_BATTERY_PERCENT && !prefs.lowBatteryAlerted -> {
                    prefs.lowBatteryAlerted = true
                    events += CareEvent(
                        key = "low_battery",
                        title = "手機電量低",
                        text = "Automatic phone event (not typed by the user): the phone battery is at $percent% and it is not charging. " +
                            "Send the user a short, caring message reminding them to charge.",
                    )
                }
            }
        }

        if (prefs.nightUsageEnabled) {
            val power = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val inNight = now.hour >= CarePrefs.NIGHT_START_HOUR && now.hour < CarePrefs.NIGHT_END_HOUR
            if (inNight && power?.isInteractive == true) {
                val nowMs = System.currentTimeMillis()
                if (prefs.nightScreenOnSince == 0L) prefs.nightScreenOnSince = nowMs
                val minutes = (nowMs - prefs.nightScreenOnSince) / 60_000
                val night = now.toLocalDate().toString()
                if (minutes >= CarePrefs.NIGHT_USAGE_MINUTES && prefs.nightAlertedFor != night) {
                    prefs.nightAlertedFor = night
                    events += CareEvent(
                        key = "night_usage",
                        title = "深夜還在用手機",
                        text = "Automatic phone event (not typed by the user): it is ${now.format(DateTimeFormatter.ofPattern("HH:mm"))} " +
                            "and the user has kept the phone screen on for about $minutes minutes late at night. " +
                            "Send a short, gentle message suggesting they rest. Do not lecture.",
                    )
                }
            } else {
                prefs.nightScreenOnSince = 0L
            }
        }
        return events
    }

    private suspend fun dispatch(prefs: CarePrefs, event: CareEvent) {
        val settings = settingsStore.awaitLoaded()
        val assistantId = prefs.assistantId ?: settings.assistantId
        val assistantName = settings.assistants.firstOrNull { it.id == assistantId }?.name?.ifBlank { null } ?: "星汐"
        val conversation = conversationRepository.getRecentConversations(assistantId, limit = 1).firstOrNull()
        if (conversation == null) {
            prefs.lastResult = "沒有可用的對話：請先和「$assistantName」聊一句"
            notify(assistantName, "${event.title}：請先打開星汐和我聊一句，我才能在這裡主動找你。", null)
            return
        }

        val before = conversation.currentMessages.size
        chatService.postNotice(
            conversationId = conversation.id,
            key = "hoshio_care:${event.key}:${System.currentTimeMillis()}",
            notice = UIMessagePart.Notice(title = event.title, text = event.text),
        )

        // 等这一轮开始再结束，最多 5 分钟
        val jobs = chatService.getGenerationJobStateFlow(conversation.id)
        val finished = withTimeoutOrNull(TimeUnit.MINUTES.toMillis(5)) {
            withTimeoutOrNull(30_000) { jobs.filterNotNull().first() }
            jobs.first { it == null }
            true
        } ?: false

        val latest = chatService.getConversation(conversation.id)
        val reply = latest?.currentMessages
            ?.drop(before)
            ?.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.toText()
            ?.trim()
            .orEmpty()
        if (reply.isNotEmpty()) {
            prefs.lastResult = "${event.title}：已送出（${LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))}）"
            notify(assistantName, reply, conversation.id.toString())
        } else {
            prefs.lastResult = if (finished) "${event.title}：AI 沒有回覆內容" else "${event.title}：等待 AI 回覆逾時"
        }
    }

    private fun notify(title: String, content: String, conversationId: String?) {
        val intent = if (conversationId != null) {
            conversationIntent(applicationContext, conversationId)
        } else {
            applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        }
        applicationContext.sendNotification(CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, CARE_NOTIFICATION_ID) {
            this.title = title
            this.content = content.take(500)
            autoCancel = true
            useDefaults = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = intent?.let {
                PendingIntent.getActivity(
                    applicationContext,
                    CARE_NOTIFICATION_ID,
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        }
    }

    companion object {
        /** 依目前设定开启或关闭每 15 分钟的检查 */
        fun sync(context: Context) {
            val workManager = WorkManager.getInstance(context)
            if (CarePrefs(context).anyEnabled) {
                workManager.enqueueUniquePeriodicWork(
                    PERIODIC_WORK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<CareWorker>(15, TimeUnit.MINUTES).build(),
                )
            } else {
                workManager.cancelUniqueWork(PERIODIC_WORK)
            }
        }

        /** 立刻测试一次：让 AI 主动打个招呼 */
        fun runTest(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                TEST_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CareWorker>()
                    .setInputData(workDataOf(KEY_TEST to true))
                    .build(),
            )
        }
    }
}
