package me.rerere.rikkahub.hoshio.rules

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 「每天幾點」的喚醒規則改用系統鬧鐘，準時把他叫醒（背景檢查每 15 分鐘一次，可能晚到）。
 * 只排最近的一個；每次檢查完、規則有變動時重新排。
 */
object WakeAlarm {
    private const val TAG = "WakeAlarm"
    private const val REQUEST = 7730

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, WakeAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun parse(s: String?) = s?.let { runCatching { LocalTime.parse(it.trim().padStart(5, '0')) }.getOrNull() }

    /** 下一個「每天幾點」規則要響的時間（毫秒），沒有就回傳 null */
    fun next(context: Context, now: LocalDateTime = LocalDateTime.now()): Long? {
        val zone = ZoneId.systemDefault()
        val today = now.toLocalDate()
        return WakeRules.load(context)
            .filter { it.enabled && it.type == "daily" }
            .mapNotNull { rule ->
                val at = parse(rule.time) ?: return@mapNotNull null
                (0..7).asSequence().mapNotNull { d ->
                    val date: LocalDate = today.plusDays(d.toLong())
                    if (rule.weekdays.isNotEmpty() && date.dayOfWeek.value !in rule.weekdays) return@mapNotNull null
                    if (d == 0 && rule.lastFiredKey == today.toString()) return@mapNotNull null
                    val time = date.atTime(at)
                    if (!time.isAfter(now)) return@mapNotNull null
                    time
                }.firstOrNull()
            }
            .minOrNull()
            ?.atZone(zone)?.toInstant()?.toEpochMilli()
    }

    fun schedule(context: Context) {
        runCatching {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = intent(context)
            val at = next(context)
            if (at == null) {
                am.cancel(pi)
                return
            }
            // 多 20 秒，確保鬧鐘響時已經過了設定的時間
            val trigger = at + 20_000
            if (canExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        }.onFailure { Log.w(TAG, "schedule failed", it) }
    }
}

class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        me.rerere.rikkahub.hoshio.care.CareWorker.checkFromAlarm(context)
    }
}
