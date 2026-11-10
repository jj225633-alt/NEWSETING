package me.rerere.rikkahub.hoshio.health

import android.content.Context
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

/** 星汐「手表／健康资料」的开关 */
class HealthPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hoshio_health", Context.MODE_PRIVATE)

    /** 用手表的睡眠纪录判断起床（没有资料时仍会用螢幕亮起判断） */
    var watchWakeEnabled: Boolean
        get() = prefs.getBoolean("watch_wake", true)
        set(value) = prefs.edit { putBoolean("watch_wake", value) }

    /** 没在运动、心率却一直很高时，他来关心 */
    var heartAlertEnabled: Boolean
        get() = prefs.getBoolean("heart_alert", false)
        set(value) = prefs.edit { putBoolean("heart_alert", value) }

    var heartAlertBpm: Int
        get() = prefs.getInt("heart_alert_bpm", 110)
        set(value) = prefs.edit { putInt("heart_alert_bpm", value) }

    /** 每周日晚上他写一封「这周的你」 */
    var weeklyReportEnabled: Boolean
        get() = prefs.getBoolean("weekly_report", true)
        set(value) = prefs.edit { putBoolean("weekly_report", value) }

    var weeklyReportFor: String?
        get() = prefs.getString("weekly_report_for", null)
        set(value) = prefs.edit { putString("weekly_report_for", value) }

    var lastHeartAlertAt: Long
        get() = prefs.getLong("heart_alert_last", 0L)
        set(value) = prefs.edit { putLong("heart_alert_last", value) }
}

/** 一段睡眠 */
data class SleepSummary(
    val start: Instant,
    val end: Instant,
    val deepMinutes: Long,
    val lightMinutes: Long,
    val remMinutes: Long,
    val awakeMinutes: Long,
) {
    val totalMinutes: Long get() = Duration.between(start, end).toMinutes()
    val asleepMinutes: Long get() = if (deepMinutes + lightMinutes + remMinutes > 0) deepMinutes + lightMinutes + remMinutes else totalMinutes - awakeMinutes

    fun describe(zone: ZoneId = ZoneId.systemDefault()): String {
        val hm = DateTimeFormatter.ofPattern("MM/dd HH:mm")
        val stages = if (deepMinutes + lightMinutes + remMinutes > 0) {
            ", deep ${deepMinutes}m, light ${lightMinutes}m, REM ${remMinutes}m, awake ${awakeMinutes}m"
        } else {
            ""
        }
        return "slept ${start.atZone(zone).format(hm)} → ${end.atZone(zone).format(hm)}, asleep ${asleepMinutes / 60}h ${asleepMinutes % 60}m$stages"
    }
}

/**
 * 星汐: 透过 Health Connect 读 Pixel Watch（Fitbit）同步过来的资料。
 * 只读、只在她授权的项目上读；每个读取都包在 runCatching 里，没授权或没资料就略过。
 */
object HealthData {
    private val RECORD_TYPES: List<KClass<out Record>> = listOf(
        SleepSessionRecord::class,
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        RespiratoryRateRecord::class,
        StepsRecord::class,
        TotalCaloriesBurnedRecord::class,
        ExerciseSessionRecord::class,
        SkinTemperatureRecord::class,
    )

    val READ_PERMISSIONS: Set<String> = RECORD_TYPES.map { HealthPermission.getReadPermission(it) }.toSet()
    val BACKGROUND_PERMISSION: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun available(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun client(context: Context): HealthConnectClient? =
        if (available(context)) runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() else null

    suspend fun granted(context: Context): Set<String> =
        client(context)?.let { runCatching { it.permissionController.getGrantedPermissions() }.getOrNull() }.orEmpty()

    suspend fun hasSleepPermission(context: Context): Boolean =
        HealthPermission.getReadPermission(SleepSessionRecord::class) in granted(context)

    private suspend inline fun <reified T : Record> read(client: HealthConnectClient, from: Instant, to: Instant): List<T> =
        runCatching {
            client.readRecords(ReadRecordsRequest(T::class, timeRangeFilter = TimeRangeFilter.between(from, to))).records
        }.getOrDefault(emptyList())

    private fun startOfToday(): Instant = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()

    private fun SleepSessionRecord.summary(): SleepSummary {
        fun minutesOf(type: Int) = stages.filter { it.stage == type }.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
        return SleepSummary(
            start = startTime,
            end = endTime,
            deepMinutes = minutesOf(SleepSessionRecord.STAGE_TYPE_DEEP),
            lightMinutes = minutesOf(SleepSessionRecord.STAGE_TYPE_LIGHT),
            remMinutes = minutesOf(SleepSessionRecord.STAGE_TYPE_REM),
            awakeMinutes = minutesOf(SleepSessionRecord.STAGE_TYPE_AWAKE),
        )
    }

    /** 最近几晚的睡眠，新的在前 */
    suspend fun sleeps(context: Context, nights: Int = 3): List<SleepSummary> {
        val client = client(context) ?: return emptyList()
        val now = Instant.now()
        return read<SleepSessionRecord>(client, now.minus(Duration.ofDays(nights.toLong() + 1)), now)
            .map { it.summary() }
            .sortedByDescending { it.end }
            .take(nights)
    }

    /** 刚结束（[withinMinutes] 分钟内、今天）而且够长的一段睡眠：代表她刚起床 */
    suspend fun recentWake(context: Context, withinMinutes: Long = 180, minSleepMinutes: Long = 180): SleepSummary? {
        val now = Instant.now()
        return sleeps(context, 2).firstOrNull { s ->
            s.end.isAfter(now.minus(Duration.ofMinutes(withinMinutes))) &&
                s.end.atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() &&
                s.totalMinutes >= minSleepMinutes
        }
    }

    private fun exerciseName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "biking"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "strength training"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "weightlifting"
        else -> "exercise #$type"
    }

    suspend fun exercises(context: Context, from: Instant, to: Instant = Instant.now()): List<String> {
        val client = client(context) ?: return emptyList()
        val hm = DateTimeFormatter.ofPattern("MM/dd HH:mm")
        return read<ExerciseSessionRecord>(client, from, to).sortedBy { it.startTime }.map {
            "${it.startTime.atZone(ZoneId.systemDefault()).format(hm)} ${it.title?.takeIf { t -> t.isNotBlank() } ?: exerciseName(it.exerciseType)} " +
                "${Duration.between(it.startTime, it.endTime).toMinutes()}m"
        }
    }

    /** 一段时间内的心率样本 (时间, bpm) */
    suspend fun heartRates(context: Context, from: Instant, to: Instant = Instant.now()): List<Pair<Instant, Long>> {
        val client = client(context) ?: return emptyList()
        return read<HeartRateRecord>(client, from, to)
            .flatMap { r -> r.samples.map { it.time to it.beatsPerMinute } }
            .filter { !it.first.isBefore(from) }
            .sortedBy { it.first }
    }

    suspend fun steps(context: Context, from: Instant, to: Instant = Instant.now()): Long? {
        val client = client(context) ?: return null
        return runCatching {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from, to)))[StepsRecord.COUNT_TOTAL]
        }.getOrNull()
    }

    /** 皮肤温度：最近一笔相对基准的变化（°C） */
    suspend fun skinTemperature(context: Context, days: Long = 3): String? {
        val client = client(context) ?: return null
        val now = Instant.now()
        val latest = read<SkinTemperatureRecord>(client, now.minus(Duration.ofDays(days)), now).maxByOrNull { it.endTime } ?: return null
        val deltas = latest.deltas.map { it.delta.inCelsius }
        if (deltas.isEmpty()) return null
        val avg = deltas.average()
        return "skin temperature ${latest.endTime.atZone(ZoneId.systemDefault()).toLocalDate()}: " +
            "${if (avg >= 0) "+" else ""}${"%.1f".format(avg)}°C vs her baseline" +
            (latest.baseline?.let { " (baseline ${"%.1f".format(it.inCelsius)}°C)" } ?: "")
    }

    /** 最近 7 天，每天一行：睡眠、步数、静止心率 */
    suspend fun week(context: Context): String {
        val client = client(context) ?: return "Health Connect is not available."
        val zone = ZoneId.systemDefault()
        val sleeps = sleeps(context, 8)
        val resting = read<RestingHeartRateRecord>(client, Instant.now().minus(Duration.ofDays(8)), Instant.now())
        val lines = (6 downTo 0).map { back ->
            val day = LocalDate.now().minusDays(back.toLong())
            val from = day.atStartOfDay(zone).toInstant()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant()
            val sleep = sleeps.firstOrNull { it.end.atZone(zone).toLocalDate() == day }
            val steps = steps(context, from, minOf(to, Instant.now()))
            val rhr = resting.filter { it.time.atZone(zone).toLocalDate() == day }.maxByOrNull { it.time }?.beatsPerMinute
            "$day (${day.dayOfWeek.toString().take(3)}): " + listOfNotNull(
                sleep?.let { "sleep ${it.asleepMinutes / 60}h${it.asleepMinutes % 60}m (${it.start.atZone(zone).toLocalTime().withSecond(0).withNano(0)}→${it.end.atZone(zone).toLocalTime().withSecond(0).withNano(0)})" },
                steps?.let { "steps $it" },
                rhr?.let { "resting HR $it" },
            ).ifEmpty { listOf("no data") }.joinToString(", ")
        }
        return (lines + listOfNotNull(skinTemperature(context, 7))).joinToString("\n")
    }

    /** 今天的摘要（给他看） */
    suspend fun today(context: Context): String {
        if (!available(context)) return "Health Connect is not available on this phone."
        val client = client(context) ?: return "Health Connect is not available."
        val granted = granted(context)
        if (granted.none { it in READ_PERMISSIONS }) return "She has not allowed access to her health data yet (首頁 → 手錶與健康)."
        val now = Instant.now()
        val start = startOfToday()
        val lines = mutableListOf<String>()
        sleeps(context, 1).firstOrNull()?.let { lines += "last sleep: ${it.describe()}" }
        steps(context, start)?.let { lines += "steps today: $it" }
        runCatching {
            client.aggregate(AggregateRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), TimeRangeFilter.between(start, now)))[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
        }.getOrNull()?.let { lines += "calories burned today: ${it.inKilocalories.toInt()} kcal" }
        read<RestingHeartRateRecord>(client, now.minus(Duration.ofDays(3)), now).maxByOrNull { it.time }?.let {
            lines += "resting heart rate: ${it.beatsPerMinute} bpm (${it.time.atZone(ZoneId.systemDefault()).toLocalDate()})"
        }
        heartRates(context, now.minus(Duration.ofHours(3))).let { hr ->
            if (hr.isNotEmpty()) {
                val last = hr.last()
                lines += "heart rate last 3h: latest ${last.second} bpm at ${last.first.atZone(ZoneId.systemDefault()).toLocalTime().withNano(0)}, " +
                    "min ${hr.minOf { it.second }}, max ${hr.maxOf { it.second }}"
            }
        }
        read<HeartRateVariabilityRmssdRecord>(client, now.minus(Duration.ofDays(2)), now).maxByOrNull { it.time }?.let {
            lines += "HRV (RMSSD): ${"%.0f".format(it.heartRateVariabilityMillis)} ms"
        }
        read<RespiratoryRateRecord>(client, now.minus(Duration.ofDays(2)), now).maxByOrNull { it.time }?.let {
            lines += "respiratory rate: ${"%.1f".format(it.rate)} /min"
        }
        exercises(context, start).takeIf { it.isNotEmpty() }?.let { lines += "exercise today: ${it.joinToString("; ")}" }
        skinTemperature(context)?.let { lines += it }
        if (lines.isEmpty()) lines += "No data yet today (the watch may not have synced; opening the Fitbit app helps)."
        return lines.joinToString("\n")
    }

    /**
     * 心率持续偏高、又不像在运动：最近 20 分钟至少 3 个样本、平均 ≥ [bpm]，
     * 同时没有运动纪录、步数很少。回传平均心率，否则 null。
     */
    suspend fun highHeartRateAtRest(context: Context, bpm: Int): Long? {
        val now = Instant.now()
        val from = now.minus(Duration.ofMinutes(20))
        val samples = heartRates(context, from)
        if (samples.size < 3) return null
        val avg = samples.map { it.second }.average().toLong()
        if (avg < bpm) return null
        if (exercises(context, now.minus(Duration.ofMinutes(40))).isNotEmpty()) return null
        val steps = steps(context, from) ?: 0L
        if (steps > 300) return null
        return avg
    }
}
