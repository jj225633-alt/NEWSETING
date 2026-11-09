package me.rerere.rikkahub.hoshio.care

import android.content.Context
import androidx.core.content.edit
import kotlin.uuid.Uuid

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

    val anyEnabled: Boolean get() = lowBatteryEnabled || nightUsageEnabled || wakeUpEnabled

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
