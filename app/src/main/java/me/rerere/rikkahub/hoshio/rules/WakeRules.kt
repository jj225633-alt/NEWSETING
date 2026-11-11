package me.rerere.rikkahub.hoshio.rules

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.hoshio.together.JsonListStore
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * 他自己订的唤醒规则（参考 Orbis 的「哨兵」）。条件成立时，背景检查会把他叫醒，
 * 带上他写给自己的 [prompt]；他醒来后可以说话、做事或继续睡。
 *
 * type:
 * - daily：每天 [time]（到 [windowEnd] 之前都算），可限定 [weekdays]（1=周一 … 7=周日）
 * - interval：每 [minutes] 分钟
 * - chat_idle：她超过 [minutes] 分钟没回他
 * - app_usage：她用 [app]（App 名称或套件名的一部分）累积超过 [minutes] 分钟（最近一小时内）
 * - screen_on：螢幕关了至少 [minutes] 分钟后又亮起来
 * - low_battery：电量 ≤ [value]%
 * - place：到达／离开（[placeEvent] = arrive | leave）名字含 [place] 的地点
 * - heart_rate：手表心率最近 20 分钟平均 ≥ [value]
 * - pc_app：她电脑上正在用 [app]（需要星汐电脑版）
 */
@Serializable
data class WakeRule(
    val id: String = Uuid.random().toString().take(8),
    val name: String,
    val type: String,
    val prompt: String,
    val enabled: Boolean = true,
    val time: String? = null,
    val windowEnd: String? = null,
    val weekdays: Set<Int> = emptySet(),
    val minutes: Int? = null,
    val app: String? = null,
    val place: String? = null,
    val placeEvent: String? = null,
    val value: Int? = null,
    val cooldownMinutes: Int = 60,
    val probability: Int = 100,
    val quietStart: String? = null,
    val quietEnd: String? = null,
    val createdBy: String = "him",
    val createdAt: Long = System.currentTimeMillis(),
    val lastFiredAt: Long = 0,
    val lastFiredKey: String? = null,
    /** 条件型规则：触发后要等条件解除才会再触发 */
    val armed: Boolean = true,
    val fireCount: Int = 0,
    /** interval：下次醒來的時間（每次觸發後隨機前後差一點，不會像打卡一樣規律） */
    val nextAt: Long = 0,
) {
    fun describe(): String = when (type) {
        "daily" -> "每天 $time" + (windowEnd?.let { "–$it" } ?: "") + (if (weekdays.isNotEmpty()) "（週${weekdays.sorted().joinToString("") { "一二三四五六日"[it - 1].toString() }}）" else "")
        "interval" -> "每 $minutes 分鐘"
        "chat_idle" -> "你 $minutes 分鐘沒回他"
        "app_usage" -> "你用「$app」超過 $minutes 分鐘"
        "screen_on" -> "螢幕關了 $minutes 分鐘以上又亮起"
        "low_battery" -> "電量 ≤ $value%"
        "place" -> (if (placeEvent == "leave") "離開" else "到達") + "「$place」"
        "heart_rate" -> "心率 ≥ $value"
        "pc_app" -> "你在電腦上用「$app」"
        else -> type
    }
}

data class WakeRuleEvent(val key: String, val title: String, val text: String)

object WakeRules : JsonListStore<WakeRule>("hoshio_wake_rules", WakeRule.serializer(), limit = 50) {
    val TYPES = listOf("daily", "interval", "chat_idle", "app_usage", "screen_on", "low_battery", "place", "heart_rate", "pc_app")

    fun hasActive(context: Context) = load(context).any { it.enabled }

    private fun parseTime(s: String?) = s?.let { runCatching { LocalTime.parse(it.trim().padStart(5, '0')) }.getOrNull() }

    private fun inQuiet(rule: WakeRule, now: LocalTime): Boolean {
        val start = parseTime(rule.quietStart) ?: return false
        val end = parseTime(rule.quietEnd) ?: return false
        return if (start <= end) now >= start && now < end else now >= start || now < end
    }

    private fun event(rule: WakeRule, detail: String) = WakeRuleEvent(
        key = "wake_rule_${rule.id}",
        title = "你設的喚醒：${rule.name}",
        text = "Automatic event (not typed by her): your own wake rule \"${rule.name}\" (${rule.describe()}) just fired: $detail. " +
            "Your note to yourself when you set it: \"${rule.prompt}\". Act on it in character.",
    )

    /** 地点到达／离开（由主动关心的地点侦测呼叫） */
    fun onPlace(context: Context, placeName: String, arrived: Boolean): List<WakeRuleEvent> {
        val now = System.currentTimeMillis()
        val fired = mutableListOf<WakeRuleEvent>()
        update(context) { list ->
            list.map { rule ->
                val match = rule.enabled && rule.type == "place" && !rule.place.isNullOrBlank() && placeName.contains(rule.place) &&
                    (rule.placeEvent == "leave") == !arrived
                if (match && now - rule.lastFiredAt >= TimeUnit.MINUTES.toMillis(rule.cooldownMinutes.toLong()) &&
                    !inQuiet(rule, LocalTime.now()) && Random.nextInt(100) < rule.probability
                ) {
                    fired += event(rule, if (arrived) "she arrived at $placeName" else "she left $placeName")
                    rule.copy(lastFiredAt = now, fireCount = rule.fireCount + 1)
                } else {
                    rule
                }
            }
        }
        return fired
    }

    /** 背景检查时评估所有规则；[lastHerMessageAt] 是她最后一次传讯息的时间 */
    suspend fun evaluate(context: Context, lastHerMessageAt: Long?): List<WakeRuleEvent> {
        val rules = load(context).filter { it.enabled && it.type != "place" }
        if (rules.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val nowDt = LocalDateTime.now()
        val fired = mutableListOf<WakeRuleEvent>()
        val updated = mutableMapOf<String, WakeRule>()

        for (rule in rules) {
            if (inQuiet(rule, nowDt.toLocalTime())) continue
            val cooled = now - rule.lastFiredAt >= TimeUnit.MINUTES.toMillis(rule.cooldownMinutes.toLong())
            // 条件型：回传 (成立吗, 说明)；时间型直接处理
            val condition: Pair<Boolean, String>? = when (rule.type) {
                "daily" -> {
                    val at = parseTime(rule.time) ?: continue
                    val end = parseTime(rule.windowEnd) ?: at.plusMinutes(60)
                    val key = nowDt.toLocalDate().toString()
                    val dayOk = rule.weekdays.isEmpty() || nowDt.dayOfWeek.value in rule.weekdays
                    val t = nowDt.toLocalTime()
                    if (dayOk && rule.lastFiredKey != key && t >= at && t < end) {
                        if (Random.nextInt(100) < rule.probability) fired += event(rule, "it is ${t.withSecond(0).withNano(0)}")
                        updated[rule.id] = rule.copy(lastFiredAt = now, lastFiredKey = key, fireCount = rule.fireCount + 1)
                    }
                    null
                }

                "interval" -> {
                    val every = TimeUnit.MINUTES.toMillis((rule.minutes ?: 60).coerceAtLeast(15).toLong())
                    val due = if (rule.nextAt > 0) rule.nextAt else maxOf(rule.lastFiredAt, rule.createdAt) + every
                    if (now >= due) {
                        if (Random.nextInt(100) < rule.probability) fired += event(rule, "about ${(now - maxOf(rule.lastFiredAt, rule.createdAt)) / 60_000} minutes have passed")
                        // 下一次隨機落在 0.8～1.25 倍間隔之間，像人一樣不準時
                        val next = now + (every * Random.nextDouble(0.8, 1.25)).toLong()
                        updated[rule.id] = rule.copy(lastFiredAt = now, fireCount = rule.fireCount + 1, nextAt = next)
                    }
                    null
                }

                "chat_idle" -> {
                    val idle = lastHerMessageAt?.let { (now - it) / 60_000 }
                    (idle != null && idle >= (rule.minutes ?: 120)) to "she has not replied for $idle minutes"
                }

                "app_usage" -> {
                    val used = appForegroundMinutes(context, rule.app.orEmpty(), 60)
                    (used >= (rule.minutes ?: 30)) to "she has used ${rule.app} for about $used minutes in the last hour"
                }

                "screen_on" -> {
                    val offMinutes = screenOnAfterOff(context, 20)
                    (offMinutes != null && offMinutes >= (rule.minutes ?: 60)) to "her screen just turned on after being off for about $offMinutes minutes"
                }

                "low_battery" -> {
                    val pct = battery(context)
                    (pct in 0..(rule.value ?: 20)) to "battery is at $pct%"
                }

                "heart_rate" -> {
                    val samples = me.rerere.rikkahub.hoshio.health.HealthData.heartRates(context, java.time.Instant.now().minusSeconds(20 * 60))
                    val avg = if (samples.size >= 3) samples.map { it.second }.average().toInt() else 0
                    (avg >= (rule.value ?: 110)) to "her heart rate averaged $avg bpm over the last 20 minutes"
                }

                "pc_app" -> {
                    val state = me.rerere.rikkahub.hoshio.pc.PcActivityStore.read(context)
                    val current = state.current
                    val online = state.lastReportAt > 0 && now - state.lastReportAt <= 3 * 60_000
                    val match = online && current != null && rule.app != null &&
                        (current.app.contains(rule.app, true) || current.title?.contains(rule.app, true) == true)
                    match to "on her computer she is using ${current?.app}" + (current?.title?.let { " ($it)" } ?: "")
                }

                else -> null
            }
            if (condition != null) {
                val (met, detail) = condition
                when {
                    met && rule.armed && cooled -> {
                        if (Random.nextInt(100) < rule.probability) fired += event(rule, detail)
                        updated[rule.id] = rule.copy(lastFiredAt = now, armed = false, fireCount = rule.fireCount + 1)
                    }
                    // 条件解除，或冷却过了仍成立：重新上膛
                    !met && !rule.armed -> updated[rule.id] = rule.copy(armed = true)
                    met && !rule.armed && cooled -> updated[rule.id] = rule.copy(armed = true)
                }
            }
        }
        if (updated.isNotEmpty()) update(context) { list -> list.map { updated[it.id] ?: it } }
        WakeAlarm.schedule(context)
        return fired
    }

    private fun battery(context: Context): Int {
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return if (plugged || level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun appLabel(context: Context, pkg: String): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** 最近 [windowMinutes] 分钟里，名称或套件名包含 [query] 的 App 在前景的分钟数（需要使用情况存取权） */
    private fun appForegroundMinutes(context: Context, query: String, windowMinutes: Long): Long {
        if (query.isBlank()) return 0
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0
        val now = System.currentTimeMillis()
        val events = runCatching { usage.queryEvents(now - TimeUnit.MINUTES.toMillis(windowMinutes), now) }.getOrNull() ?: return 0
        val e = UsageEvents.Event()
        var total = 0L
        var startedAt: Long? = null
        val labels = mutableMapOf<String, Boolean>()
        fun matches(pkg: String) = labels.getOrPut(pkg) { pkg.contains(query, true) || appLabel(context, pkg).contains(query, true) }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (matches(e.packageName) && startedAt == null) startedAt = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED -> if (matches(e.packageName)) startedAt?.let { total += e.timeStamp - it; startedAt = null }
            }
        }
        startedAt?.let { total += now - it }
        return total / 60_000
    }

    /** 最近 [withinMinutes] 分钟内螢幕从关到亮：回传关了几分钟；没有就 null */
    private fun screenOnAfterOff(context: Context, withinMinutes: Long): Long? {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usage.queryEvents(now - TimeUnit.HOURS.toMillis(24), now) }.getOrNull() ?: return null
        val e = UsageEvents.Event()
        var offAt = 0L
        var result: Long? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> offAt = e.timeStamp
                UsageEvents.Event.SCREEN_INTERACTIVE -> if (offAt > 0 && now - e.timeStamp <= TimeUnit.MINUTES.toMillis(withinMinutes)) {
                    result = (e.timeStamp - offAt) / 60_000
                }
            }
        }
        return result
    }
}
