package me.rerere.rikkahub.hoshio.care

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** 用户设定的地点，例如家、公司 */
@Serializable
data class CarePlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = CarePrefs.DEFAULT_PLACE_RADIUS,
)

/** 每个地点目前判断在里面还是外面，以及上次触发时间（避免在边界来回跳） */
@Serializable
data class CarePlaceState(
    val inside: Boolean,
    val lastEventAt: Long = 0L,
)

private val careJson = Json { ignoreUnknownKeys = true }

/**
 * 星汐「主动关心」的设定与状态，存在独立的 SharedPreferences，不动 RikkaHub 的设定档。
 */
class CarePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hoshio_care", Context.MODE_PRIVATE)

    /** 由哪个助手主动关心；在某个助手的设定页打开时记录下来 */
    var assistantId: Uuid?
        get() = prefs.getString(KEY_ASSISTANT, null)?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        set(value) = prefs.edit { putString(KEY_ASSISTANT, value?.toString()) }

    var lowBatteryEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOW_BATTERY, false)
        set(value) = prefs.edit { putBoolean(KEY_LOW_BATTERY, value) }

    var nightUsageEnabled: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_USAGE, false)
        set(value) = prefs.edit { putBoolean(KEY_NIGHT_USAGE, value) }

    var wakeUpEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_UP, false)
        set(value) = prefs.edit { putBoolean(KEY_WAKE_UP, value) }

    var placesEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLACES_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_PLACES_ENABLED, value) }

    var places: List<CarePlace>
        get() = prefs.getString(KEY_PLACES, null)
            ?.let { runCatching { careJson.decodeFromString<List<CarePlace>>(it) }.getOrNull() }
            .orEmpty()
        set(value) = prefs.edit { putString(KEY_PLACES, careJson.encodeToString(value)) }

    var screenObservationEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_OBS, false)
        set(value) = prefs.edit { putBoolean(KEY_SCREEN_OBS, value) }

    /** "ignored"：她没理你超过 N 分钟、螢幕又亮着时才偷看；"interval"：固定每 N 分钟偷看 */
    var screenObservationMode: String
        get() = prefs.getString(KEY_SCREEN_OBS_MODE, SCREEN_MODE_IGNORED) ?: SCREEN_MODE_IGNORED
        set(value) = prefs.edit { putString(KEY_SCREEN_OBS_MODE, value) }

    var screenObservationIntervalMinutes: Int
        get() = prefs.getInt(KEY_SCREEN_OBS_INTERVAL, 60)
        set(value) = prefs.edit { putInt(KEY_SCREEN_OBS_INTERVAL, value) }

    var lastScreenObservationAt: Long
        get() = prefs.getLong(KEY_SCREEN_OBS_LAST, 0L)
        set(value) = prefs.edit { putLong(KEY_SCREEN_OBS_LAST, value) }

    var freeTimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_FREE_TIME, false)
        set(value) = prefs.edit { putBoolean(KEY_FREE_TIME, value) }

    var freeTimeIdleMinutes: Int
        get() = prefs.getInt(KEY_FREE_TIME_IDLE, 120)
        set(value) = prefs.edit { putInt(KEY_FREE_TIME_IDLE, value) }

    var lastFreeTimeAt: Long
        get() = prefs.getLong(KEY_FREE_TIME_LAST, 0L)
        set(value) = prefs.edit { putLong(KEY_FREE_TIME_LAST, value) }

    var freeTimeCountDate: String?
        get() = prefs.getString(KEY_FREE_TIME_COUNT_DATE, null)
        set(value) = prefs.edit { putString(KEY_FREE_TIME_COUNT_DATE, value) }

    var freeTimeCount: Int
        get() = prefs.getInt(KEY_FREE_TIME_COUNT, 0)
        set(value) = prefs.edit { putInt(KEY_FREE_TIME_COUNT, value) }

    /** 睡前问候：到了设定的时间她还醒着，就来道晚安 */
    var bedtimeEnabled: Boolean
        get() = prefs.getBoolean("bedtime_enabled", false)
        set(value) = prefs.edit { putBoolean("bedtime_enabled", value) }

    var bedtimeHour: Int
        get() = prefs.getInt("bedtime_hour", 23)
        set(value) = prefs.edit { putInt("bedtime_hour", value) }

    /** 最近一次道晚安是哪一晚 */
    var bedtimeGreetedFor: String?
        get() = prefs.getString("bedtime_greeted_for", null)
        set(value) = prefs.edit { putString("bedtime_greeted_for", value) }

    /** 每天自动写日记 */
    var diaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIARY, false)
        set(value) = prefs.edit { putBoolean(KEY_DIARY, value) }

    /** 已经尝试写过日记的日期（写成功或那天没聊天），避免重复呼叫模型 */
    var diaryAttemptedDates: Set<String>
        get() = prefs.getStringSet(KEY_DIARY_ATTEMPTED, emptySet()).orEmpty()
        set(value) = prefs.edit { putStringSet(KEY_DIARY_ATTEMPTED, value.sortedDescending().take(30).toSet()) }

    /** 常驻模式：用前台服务每几分钟检查一次，比 15 分钟的一般检查即时 */
    var alwaysOnEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_ON, false)
        set(value) = prefs.edit { putBoolean(KEY_ALWAYS_ON, value) }

    val anyEnabled: Boolean get() = lowBatteryEnabled || nightUsageEnabled || wakeUpEnabled || placesEnabled || screenObservationEnabled || freeTimeEnabled || diaryEnabled || bedtimeEnabled

    // ---- 可自订的条件 ----

    var lowBatteryPercent: Int
        get() = prefs.getInt("low_battery_percent", LOW_BATTERY_PERCENT)
        set(value) = prefs.edit { putInt("low_battery_percent", value) }

    var nightStartHour: Int
        get() = prefs.getInt("night_start_hour", NIGHT_START_HOUR)
        set(value) = prefs.edit { putInt("night_start_hour", value) }

    var nightEndHour: Int
        get() = prefs.getInt("night_end_hour", NIGHT_END_HOUR)
        set(value) = prefs.edit { putInt("night_end_hour", value) }

    var nightUsageMinutes: Int
        get() = prefs.getInt("night_usage_minutes", NIGHT_USAGE_MINUTES.toInt())
        set(value) = prefs.edit { putInt("night_usage_minutes", value) }

    var wakeMinSleepHours: Int
        get() = prefs.getInt("wake_min_sleep_hours", WAKE_MIN_SLEEP_HOURS.toInt())
        set(value) = prefs.edit { putInt("wake_min_sleep_hours", value) }

    var wakeStartHour: Int
        get() = prefs.getInt("wake_start_hour", WAKE_START_HOUR)
        set(value) = prefs.edit { putInt("wake_start_hour", value) }

    var wakeEndHour: Int
        get() = prefs.getInt("wake_end_hour", WAKE_END_HOUR)
        set(value) = prefs.edit { putInt("wake_end_hour", value) }

    var diaryWriteHour: Int
        get() = prefs.getInt("diary_write_hour", DIARY_WRITE_HOUR)
        set(value) = prefs.edit { putInt("diary_write_hour", value) }

    /** 现在是否在深夜时段（可跨午夜，例如 23 点到 6 点） */
    fun isNight(hour: Int): Boolean {
        val start = nightStartHour
        val end = nightEndHour
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    // ---- 运行状态 ----

    /** 这次低电量已经提醒过；充电或电量回升后才会再提醒 */
    var lowBatteryAlerted: Boolean
        get() = prefs.getBoolean(KEY_LOW_BATTERY_ALERTED, false)
        set(value) = prefs.edit { putBoolean(KEY_LOW_BATTERY_ALERTED, value) }

    /** 深夜开始持续亮屏的时间，0 表示目前没有 */
    var nightScreenOnSince: Long
        get() = prefs.getLong(KEY_NIGHT_SINCE, 0L)
        set(value) = prefs.edit { putLong(KEY_NIGHT_SINCE, value) }

    /** 最近一次深夜提醒属于哪一晚（以开始那天的日期记录），一晚只提醒一次 */
    var nightAlertedFor: String?
        get() = prefs.getString(KEY_NIGHT_ALERTED_FOR, null)
        set(value) = prefs.edit { putString(KEY_NIGHT_ALERTED_FOR, value) }

    /** 最近一次早安问候是哪一天，一天只问候一次 */
    var wakeGreetedFor: String?
        get() = prefs.getString(KEY_WAKE_GREETED_FOR, null)
        set(value) = prefs.edit { putString(KEY_WAKE_GREETED_FOR, value) }

    var placeStates: Map<String, CarePlaceState>
        get() = prefs.getString(KEY_PLACE_STATES, null)
            ?.let { runCatching { careJson.decodeFromString<Map<String, CarePlaceState>>(it) }.getOrNull() }
            .orEmpty()
        set(value) = prefs.edit { putString(KEY_PLACE_STATES, careJson.encodeToString(value)) }

    var lastResult: String?
        get() = prefs.getString(KEY_LAST_RESULT, null)
        set(value) = prefs.edit { putString(KEY_LAST_RESULT, value) }

    companion object {
        private const val KEY_ASSISTANT = "assistant_id"
        private const val KEY_LOW_BATTERY = "low_battery_enabled"
        private const val KEY_NIGHT_USAGE = "night_usage_enabled"
        private const val KEY_LOW_BATTERY_ALERTED = "low_battery_alerted"
        private const val KEY_NIGHT_SINCE = "night_screen_on_since"
        private const val KEY_NIGHT_ALERTED_FOR = "night_alerted_for"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_WAKE_UP = "wake_up_enabled"
        private const val KEY_WAKE_GREETED_FOR = "wake_greeted_for"
        private const val KEY_PLACES_ENABLED = "places_enabled"
        private const val KEY_ALWAYS_ON = "always_on_enabled"
        private const val KEY_SCREEN_OBS = "screen_observation_enabled"
        private const val KEY_SCREEN_OBS_INTERVAL = "screen_observation_interval"
        private const val KEY_SCREEN_OBS_LAST = "screen_observation_last"
        private const val KEY_SCREEN_OBS_MODE = "screen_observation_mode"

        private const val KEY_FREE_TIME = "free_time_enabled"
        private const val KEY_DIARY = "diary_enabled"
        private const val KEY_DIARY_ATTEMPTED = "diary_attempted_dates"

        /** 每天这个时间之后写当天的日记 */
        const val DIARY_WRITE_HOUR = 23
        private const val KEY_FREE_TIME_IDLE = "free_time_idle_minutes"
        private const val KEY_FREE_TIME_LAST = "free_time_last"
        private const val KEY_FREE_TIME_COUNT_DATE = "free_time_count_date"
        private const val KEY_FREE_TIME_COUNT = "free_time_count"

        /** 自由时间：凌晨 0 点到这个时间不打扰 */
        const val FREE_TIME_QUIET_END_HOUR = 8
        const val FREE_TIME_MAX_PER_DAY = 6

        const val SCREEN_MODE_IGNORED = "ignored"
        const val SCREEN_MODE_INTERVAL = "interval"
        private const val KEY_PLACES = "places"
        private const val KEY_PLACE_STATES = "place_states"

        const val DEFAULT_PLACE_RADIUS = 200

        /** 离开要超出半径这么多才算，避免定位误差造成来回跳 */
        const val PLACE_EXIT_MARGIN_METERS = 150

        /** 定位误差大于这个值时不判断进出 */
        const val PLACE_MAX_ACCURACY_METERS = 500f

        /** 同一个地点两次提醒至少间隔 */
        const val PLACE_COOLDOWN_MINUTES = 30L

        const val LOW_BATTERY_PERCENT = 20
        const val LOW_BATTERY_RESET_PERCENT = 30

        /** 深夜时段 00:00–05:00 */
        const val NIGHT_START_HOUR = 0
        const val NIGHT_END_HOUR = 5

        /** 深夜连续亮屏超过这么久才提醒 */
        const val NIGHT_USAGE_MINUTES = 20L

        /** 起床判断：螢幕关闭至少这么久（睡眠），并在这个时段内第一次亮起 */
        const val WAKE_MIN_SLEEP_HOURS = 4L
        const val WAKE_START_HOUR = 4
        const val WAKE_END_HOUR = 13

        /** 起床超过这么久才检查到，就不再补问早安 */
        const val WAKE_MAX_DELAY_MINUTES = 90L
    }
}
