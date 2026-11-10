package me.rerere.rikkahub.hoshio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.hoshio.backup.AutoBackupPrefs
import me.rerere.rikkahub.hoshio.care.CarePrefs
import me.rerere.rikkahub.hoshio.music.HoshioNotificationListener
import me.rerere.rikkahub.hoshio.screen.AppLock
import me.rerere.rikkahub.hoshio.screen.HoshioAccessibilityService
import me.rerere.rikkahub.utils.hasUsageStatsPermission

/** 一项检查的结果 */
data class CheckItem(
    val title: String,
    val ok: Boolean,
    /** 需要这项的功能 */
    val neededFor: String,
    /** 没通过时怎么修 */
    val fix: String,
    /** 点了能直接去修的设定页 */
    val settingsIntent: Intent? = null,
)

/**
 * 星汐「自我检查」：权限和设定有没有开好，哪个功能会受影响、怎么修。
 * 人类在检查页看，AI 用 hoshio_self_check 工具看。
 */
object SelfCheck {
    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun appSettings(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    fun run(context: Context): List<CheckItem> {
        val care = CarePrefs(context)
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val items = mutableListOf<CheckItem>()

        items += CheckItem(
            "通知權限",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS),
            "主動關心、信、通知直接回覆",
            "設定 → 應用程式 → 星汐 → 通知 → 允許",
            appSettings(context),
        )
        items += CheckItem(
            "電池不受限制",
            power?.isIgnoringBatteryOptimizations(context.packageName) == true,
            "主動關心、到家提醒、約好的時間準時觸發",
            "設定 → 應用程式 → 星汐 → 電池 → 不受限制",
            appSettings(context),
        )
        val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        items += CheckItem("定位權限", fine, "目前位置、天氣、到家／出門提醒", "設定 → 應用程式 → 星汐 → 權限 → 位置", appSettings(context))
        if (care.placesEnabled) {
            items += CheckItem("背景定位（一律允許）", background, "到家／出門提醒", "權限 → 位置 → 一律允許", appSettings(context))
        }
        items += CheckItem(
            "使用情況存取",
            context.hasUsageStatsPermission(),
            "起床打招呼、螢幕使用時間",
            "設定 → 應用程式 → 特殊應用程式存取權 → 使用情況存取權 → 星汐",
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        )
        val accessibilityOn = HoshioAccessibilityService.isEnabledInSettings(context)
        items += CheckItem(
            "無障礙服務",
            accessibilityOn && HoshioAccessibilityService.isRunning,
            "看螢幕、偷看螢幕、應用鎖",
            if (accessibilityOn) "已開啟但沒在執行：關掉再打開一次" else "設定 → 無障礙 → 已下載的應用程式 → 星汐",
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        items += CheckItem(
            "通知存取",
            HoshioNotificationListener.isEnabled(context),
            "一起聽音樂",
            "設定 → 通知 → 裝置和應用程式通知（通知存取）→ 星汐",
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )
        val backup = AutoBackupPrefs(context)
        items += CheckItem(
            "自動備份",
            backup.enabled && backup.lastResult?.startsWith("已備份") == true,
            "資料保存到雲端",
            if (!backup.enabled) "首頁 → 備份與還原 → 打開自動備份（要先設定 WebDAV 或 S3）" else "最近一次：${backup.lastResult ?: "還沒備份過"}",
        )
        return items
    }

    /** 给 AI 看的一段摘要 */
    fun summary(context: Context): String {
        val care = CarePrefs(context)
        return buildString {
            appendLine("Permissions and settings:")
            run(context).forEach { appendLine("- ${it.title}: ${if (it.ok) "OK" else "NOT OK"} (needed for ${it.neededFor}; fix: ${it.fix})") }
            appendLine("Proactive care enabled: " + listOfNotNull(
                "low battery".takeIf { care.lowBatteryEnabled },
                "late night".takeIf { care.nightUsageEnabled },
                "wake up".takeIf { care.wakeUpEnabled },
                "bedtime".takeIf { care.bedtimeEnabled },
                "places".takeIf { care.placesEnabled },
                "screen peek".takeIf { care.screenObservationEnabled },
                "free time".takeIf { care.freeTimeEnabled },
                "diary".takeIf { care.diaryEnabled },
            ).ifEmpty { listOf("none") }.joinToString(", "))
            appendLine("Always-on mode: ${care.alwaysOnEnabled}")
            appendLine("App lock allowed by her: ${AppLock.allowed(context)}")
            appendLine("Last proactive care result: ${care.lastResult ?: "none"}")
            val wakes = me.rerere.rikkahub.hoshio.life.WakeLogStore.load(context).take(5)
            if (wakes.isNotEmpty()) {
                appendLine("Recent wake-ups:")
                wakes.forEach { appendLine("- ${java.time.Instant.ofEpochMilli(it.at)}: ${it.trigger} -> ${it.action} ${it.note.take(60)}") }
            }
        }
    }
}
