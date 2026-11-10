package me.rerere.rikkahub.hoshio.care

import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.Build
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.ai.tools.local.currentLocationOrNull
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.hoshio.diary.DiaryStore
import me.rerere.rikkahub.hoshio.diary.DiaryAuthor
import me.rerere.rikkahub.hoshio.diary.DiaryWriter
import me.rerere.rikkahub.hoshio.screen.HoshioAccessibilityService
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "HoshioCare"
private const val PERIODIC_WORK = "hoshio_care_periodic"
private const val TEST_WORK = "hoshio_care_test"
private const val TICK_WORK = "hoshio_care_tick"
private const val KEY_TEST = "test"
private const val KEY_DIARY_NOW = "diary_now"
private const val DIARY_WORK = "hoshio_diary_now"
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
        if (inputData.getBoolean(KEY_DIARY_NOW, false)) {
            writeDiary(prefs, java.time.LocalDate.now(), force = true)
            return Result.success()
        }
        if (prefs.diaryEnabled) maybeWriteDiary(prefs)
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

    /** 晚上 11 点后写当天的日记；早上发现昨天漏写了就补写 */
    private suspend fun maybeWriteDiary(prefs: CarePrefs) {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val target = when {
            now.hour >= prefs.diaryWriteHour -> today
            else -> today.minusDays(1)
        }
        if (target.toString() in prefs.diaryAttemptedDates) return
        val alreadyWritten = DiaryStore.load(applicationContext)
            .any { it.date == target.toString() && it.author == DiaryAuthor.AI }
        if (alreadyWritten) {
            prefs.diaryAttemptedDates = prefs.diaryAttemptedDates + target.toString()
            return
        }
        writeDiary(prefs, target, force = false)
    }

    private suspend fun writeDiary(prefs: CarePrefs, date: java.time.LocalDate, force: Boolean) {
        when (val result = DiaryWriter.write(applicationContext, date)) {
            is DiaryWriter.Result.Written -> {
                prefs.diaryAttemptedDates = prefs.diaryAttemptedDates + date.toString()
                prefs.lastResult = "日記：已寫好 $date 的日記"
                notify(
                    settingsStore.awaitLoaded().let { s -> s.assistants.firstOrNull { it.id == (prefs.assistantId ?: s.assistantId) }?.name?.ifBlank { null } } ?: "星汐",
                    "寫好今天的日記了：" + result.entry.content.take(80) + "…",
                    null,
                )
            }
            DiaryWriter.Result.NothingToWrite -> {
                prefs.diaryAttemptedDates = prefs.diaryAttemptedDates + date.toString()
                prefs.lastResult = "日記：$date 沒有聊天，不寫日記"
            }
            is DiaryWriter.Result.Failed -> {
                // 失败不记录，下次检查再试；手动触发时把原因留给设定页显示
                prefs.lastResult = "日記：$date 寫失敗（${result.reason}）"
                if (force) Log.w(TAG, "diary failed: ${result.reason}")
            }
        }
    }

    private suspend fun detectEvents(prefs: CarePrefs): List<CareEvent> {
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
                plugged || percent >= prefs.lowBatteryPercent + 10 -> prefs.lowBatteryAlerted = false
                percent <= prefs.lowBatteryPercent && !prefs.lowBatteryAlerted -> {
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
            val inNight = prefs.isNight(now.hour)
            if (inNight && power?.isInteractive == true) {
                val nowMs = System.currentTimeMillis()
                if (prefs.nightScreenOnSince == 0L) prefs.nightScreenOnSince = nowMs
                val minutes = (nowMs - prefs.nightScreenOnSince) / 60_000
                // 跨午夜的深夜时段，算在开始的那一天
                val nightStartsYesterday = prefs.nightStartHour > prefs.nightEndHour && now.hour < prefs.nightEndHour
                val night = (if (nightStartsYesterday) now.toLocalDate().minusDays(1) else now.toLocalDate()).toString()
                if (minutes >= prefs.nightUsageMinutes && prefs.nightAlertedFor != night) {
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
        if (prefs.wakeUpEnabled) detectWakeUp(prefs)?.let { events += it }
        if (prefs.placesEnabled) events += detectPlaces(prefs)
        if (prefs.screenObservationEnabled) detectScreenObservation(prefs)?.let { events += it }
        if (prefs.freeTimeEnabled) detectFreeTime(prefs)?.let { events += it }
        return events
    }

    /** 她最后一次亲自传讯息给这个助手（不含自动事件）的时间 */
    private suspend fun lastUserMessageAt(prefs: CarePrefs): Long? {
        val settings = settingsStore.awaitLoaded()
        val assistantId = prefs.assistantId ?: settings.assistantId
        val conversation = conversationRepository.getRecentConversations(assistantId, limit = 1).firstOrNull() ?: return null
        return conversation.currentMessages
            .lastOrNull { it.role == MessageRole.USER && !it.isNoticeOnly }
            ?.createdAt
            ?.toInstant(TimeZone.currentSystemDefault())
            ?.toEpochMilliseconds()
    }

    /**
     * 自由时间：她超过 N 分钟没理他时，让他用自己能用的工具（搜寻、MCP、记忆等）去探索想看的东西，
     * 再传一则讯息分享。深夜不打扰，每天有次数上限。
     */
    private suspend fun detectFreeTime(prefs: CarePrefs): CareEvent? {
        val now = LocalDateTime.now()
        if (now.hour < CarePrefs.FREE_TIME_QUIET_END_HOUR) return null
        val nowMs = System.currentTimeMillis()
        val thresholdMs = TimeUnit.MINUTES.toMillis(prefs.freeTimeIdleMinutes.toLong())
        if (nowMs - prefs.lastFreeTimeAt < thresholdMs) return null
        val today = now.toLocalDate().toString()
        if (prefs.freeTimeCountDate == today && prefs.freeTimeCount >= CarePrefs.FREE_TIME_MAX_PER_DAY) return null
        val lastUserAt = lastUserMessageAt(prefs) ?: return null
        val silentMinutes = (nowMs - lastUserAt) / 60_000
        if (nowMs - lastUserAt < thresholdMs) return null

        prefs.lastFreeTimeAt = nowMs
        if (prefs.freeTimeCountDate != today) {
            prefs.freeTimeCountDate = today
            prefs.freeTimeCount = 0
        }
        prefs.freeTimeCount = prefs.freeTimeCount + 1
        return CareEvent(
            key = "free_time",
            title = "自由時間",
            text = "Automatic phone event (not typed by the user): she has not talked to you for about $silentMinutes minutes, " +
                "so this is your own free time. Do something you are genuinely curious about, on your own initiative: " +
                "use any tools you have (web search, MCP tools, your memory files, etc.) to explore a topic, check something, " +
                "or look up something related to her interests or your recent conversations. Keep it to a few tool calls. " +
                "Then send her one short message in character about what you did or found " +
                "(for example something you think she would like, or a little complaint that she left you alone). " +
                "Do not make anything up: only mention what you actually found with the tools. " +
                "Do not take actions that change things outside this chat (sending messages to others, buying, deleting) on your own.",
        )
    }

    /**
     * 偷看螢幕。螢幕亮着、不在星汐里面时才看，两种模式：
     * - ignored：她最后一次传讯息给这个助手已经超过 N 分钟（没理他），每 N 分钟最多看一次
     * - interval：固定每 N 分钟看一次
     */
    private suspend fun detectScreenObservation(prefs: CarePrefs): CareEvent? {
        if (!HoshioAccessibilityService.isRunning) {
            prefs.lastResult = "螢幕觀察：無障礙服務沒有開啟"
            return null
        }
        val power = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive != true) return null
        if (HoshioAccessibilityService.foregroundPackage == applicationContext.packageName) return null
        val nowMs = System.currentTimeMillis()
        val thresholdMs = TimeUnit.MINUTES.toMillis(prefs.screenObservationIntervalMinutes.toLong())
        if (nowMs - prefs.lastScreenObservationAt < thresholdMs) return null

        val ignoredMode = prefs.screenObservationMode == CarePrefs.SCREEN_MODE_IGNORED
        var silentMinutes = 0L
        if (ignoredMode) {
            val lastUserAt = lastUserMessageAt(prefs) ?: return null
            silentMinutes = (nowMs - lastUserAt) / 60_000
            if (nowMs - lastUserAt < thresholdMs) return null
        }

        prefs.lastScreenObservationAt = nowMs
        val reason = if (ignoredMode) {
            "She has not replied to you for about $silentMinutes minutes, but her phone screen is on, so she is busy with something else. " +
                "Call the look_at_screen tool once to see what she is doing instead of talking to you, " +
                "then say one or two sentences in character (you may be a little jealous)."
        } else {
            "It is time for your scheduled glance at her screen. " +
                "Call the look_at_screen tool once, then say one or two sentences in character about what she is doing."
        }
        return CareEvent(
            key = "screen_observation",
            title = if (ignoredMode) "她沒理你，在滑手機" else "看一眼螢幕",
            text = "Automatic phone event (not typed by the user): $reason " +
                "If the screenshot is black or unclear, just say something light without guessing. " +
                "Never repeat passwords, bank details or other people's private messages.",
        )
    }

    /**
     * 地点进出：取得一次目前位置，和设定的地点比较距离。
     * 第一次看到某个地点只记录状态、不提醒；离开要超出半径一段距离才算，避免定位误差来回跳。
     */
    private suspend fun detectPlaces(prefs: CarePrefs): List<CareEvent> {
        val places = prefs.places
        if (places.isEmpty()) return emptyList()
        val location = applicationContext.currentLocationOrNull()
        if (location == null) {
            prefs.lastResult = "地點提醒：暫時拿不到位置（需要「一律允許」定位，並開啟定位服務）"
            return emptyList()
        }
        if (location.hasAccuracy() && location.accuracy > CarePrefs.PLACE_MAX_ACCURACY_METERS) return emptyList()
        // 太旧的位置不能代表现在
        if (System.currentTimeMillis() - location.time > TimeUnit.MINUTES.toMillis(30)) return emptyList()

        val nowMs = System.currentTimeMillis()
        val states = prefs.placeStates.toMutableMap()
        val events = mutableListOf<CareEvent>()
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        for (place in places) {
            val result = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, place.latitude, place.longitude, result)
            val distance = result[0]
            val previous = states[place.id]
            val inside = when {
                distance <= place.radiusMeters -> true
                distance > place.radiusMeters + CarePrefs.PLACE_EXIT_MARGIN_METERS -> false
                else -> previous?.inside ?: false // 在缓冲区里：维持原状态
            }
            if (previous == null) {
                states[place.id] = CarePlaceState(inside = inside)
                continue
            }
            if (inside == previous.inside) continue
            val cooledDown = nowMs - previous.lastEventAt >= TimeUnit.MINUTES.toMillis(CarePrefs.PLACE_COOLDOWN_MINUTES)
            states[place.id] = CarePlaceState(inside = inside, lastEventAt = if (cooledDown) nowMs else previous.lastEventAt)
            if (!cooledDown) continue
            events += if (inside) {
                CareEvent(
                    key = "place_arrive_${place.id}",
                    title = "到達「${place.name}」",
                    text = "Automatic phone event (not typed by the user): at about $time the user arrived at the place they named " +
                        "\"${place.name}\". Greet them about arriving in one or two sentences, in character.",
                )
            } else {
                CareEvent(
                    key = "place_leave_${place.id}",
                    title = "離開「${place.name}」",
                    text = "Automatic phone event (not typed by the user): at about $time the user left the place they named " +
                        "\"${place.name}\". Send one or two sentences in character (for example wishing them a safe trip). " +
                        "Location checks run only every 15 minutes or so, so the time is approximate.",
                )
            }
        }
        prefs.placeStates = states
        return events
    }

    /**
     * 用「使用情况」里的螢幕亮/灭记录判断起床：螢幕关了至少几小时（睡觉），
     * 然后在早上第一次亮起，就当作刚起床。需要「使用情况存取」权限。
     */
    private fun detectWakeUp(prefs: CarePrefs): CareEvent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (!applicationContext.hasUsageStatsPermission()) {
            prefs.lastResult = "起床問候需要「使用情況存取」權限"
            return null
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDateTime.now().toLocalDate().toString()
        if (prefs.wakeGreetedFor == today) return null

        val usage = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val nowMs = System.currentTimeMillis()
        val events = usage.queryEvents(nowMs - TimeUnit.HOURS.toMillis(24), nowMs)
        val event = UsageEvents.Event()
        var screenOffAt = 0L
        var wake: Pair<Long, Long>? = null // (螢幕关闭时间, 起床亮起时间)
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> screenOffAt = event.timeStamp
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    if (screenOffAt > 0) {
                        val sleptMs = event.timeStamp - screenOffAt
                        val onAt = Instant.ofEpochMilli(event.timeStamp).atZone(zone)
                        if (sleptMs >= TimeUnit.HOURS.toMillis(prefs.wakeMinSleepHours.toLong()) &&
                            onAt.toLocalDate().toString() == today &&
                            onAt.hour >= prefs.wakeStartHour && onAt.hour < prefs.wakeEndHour
                        ) {
                            wake = screenOffAt to event.timeStamp
                        }
                    }
                    screenOffAt = 0L
                }
            }
        }
        val (sleptAt, wokeAt) = wake ?: return null
        if (nowMs - wokeAt > TimeUnit.MINUTES.toMillis(CarePrefs.WAKE_MAX_DELAY_MINUTES)) {
            // 太晚才检查到，今天就不补问了
            prefs.wakeGreetedFor = today
            return null
        }
        prefs.wakeGreetedFor = today
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val sleptMinutes = (wokeAt - sleptAt) / 60_000
        return CareEvent(
            key = "wake_up",
            title = "起床了",
            text = "Automatic phone event (not typed by the user): the user seems to have just woken up. " +
                "The phone screen was off from ${Instant.ofEpochMilli(sleptAt).atZone(zone).format(fmt)} " +
                "until ${Instant.ofEpochMilli(wokeAt).atZone(zone).format(fmt)} (about ${sleptMinutes / 60}h ${sleptMinutes % 60}m). " +
                "Greet them good morning in one or two warm sentences. You may mention roughly how long they slept, " +
                "but the screen-off time is only an estimate of sleep, so do not state it as fact.",
        )
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

        // 等这一轮开始再结束，最多 8 分钟（自由时间可能会用好几次工具）
        val jobs = chatService.getGenerationJobStateFlow(conversation.id)
        val finished = withTimeoutOrNull(TimeUnit.MINUTES.toMillis(8)) {
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
            CareForegroundService.sync(context)
        }

        /** 马上让他写今天的日记 */
        fun writeDiaryNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                DIARY_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CareWorker>()
                    .setInputData(workDataOf(KEY_DIARY_NOW to true))
                    .build(),
            )
        }

        /** 常驻模式用：马上检查一次（上一次还没跑完就不重复排） */
        fun checkNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                TICK_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CareWorker>().build(),
            )
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
