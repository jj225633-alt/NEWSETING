package me.rerere.rikkahub.hoshio.screen

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 星汐「应用锁」：她允许之后，他可以把她切回星汐，或暂时锁住其他 App（最多 60 分钟）。
 * 电话、简讯、设定、键盘、桌面等必要的 App 永远不会被锁；通知栏随时可以一键解除。
 */
object AppLock {
    private const val PREFS = "hoshio_app_lock"
    const val MAX_MINUTES = 60
    private const val NOTIFICATION_ID = 7305
    const val ACTION_UNLOCK = "app.hoshio.applock.UNLOCK"

    /** 永远不锁的 App（关键字比对套件名称） */
    private val ESSENTIAL_KEYWORDS = listOf(
        "dialer", "android.phone", "telecom", "contacts", "messaging", "mms", "sms",
        "settings", "systemui", "launcher", "inputmethod", "keyboard", "gboard",
        "packageinstaller", "permissioncontroller", "emergency", "safetyhub", "camera",
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 她有没有允许他使用应用锁 */
    fun allowed(context: Context): Boolean = prefs(context).getBoolean("allowed", false)

    fun setAllowed(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean("allowed", value) }
        if (!value) unlock(context, byHer = false)
    }

    fun lockedUntil(context: Context): Long = prefs(context).getLong("until", 0L)

    fun isLocked(context: Context): Boolean = lockedUntil(context) > System.currentTimeMillis()

    fun reason(context: Context): String = prefs(context).getString("reason", "").orEmpty()

    fun isEssential(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return true
        val defaultLauncher = runCatching {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName
        }.getOrNull()
        if (packageName == defaultLauncher) return true
        val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        if (ime.startsWith("$packageName/")) return true
        return ESSENTIAL_KEYWORDS.any { packageName.contains(it, ignoreCase = true) }
    }

    fun lock(context: Context, minutes: Int, reason: String): Long {
        val until = System.currentTimeMillis() + minutes.coerceIn(1, MAX_MINUTES) * 60_000L
        prefs(context).edit {
            putLong("until", until)
            putString("reason", reason.take(100))
        }
        showLockNotification(context, until, reason)
        return until
    }

    fun unlock(context: Context, byHer: Boolean) {
        val wasLocked = isLocked(context)
        prefs(context).edit {
            putLong("until", 0L)
            if (byHer && wasLocked) putLong("her_unlocked_at", System.currentTimeMillis())
        }
        context.cancelNotification(NOTIFICATION_ID)
    }

    /** 最近一次她自己按「解除」的时间，让他知道 */
    fun herUnlockedAt(context: Context): Long? = prefs(context).getLong("her_unlocked_at", 0L).takeIf { it > 0 }

    /** 打开星汐（回到首页／最近的对话） */
    fun bringHerBack(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        return runCatching { context.startActivity(launch) }.isSuccess
    }

    private fun showLockNotification(context: Context, until: Long, reason: String) {
        val time = Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        val unlock = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, AppLockUnlockReceiver::class.java).setAction(ACTION_UNLOCK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.sendNotification(CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, NOTIFICATION_ID) {
            title = "他把其他 App 鎖到 $time"
            content = (if (reason.isNotBlank()) "「$reason」\n" else "") + "真的需要用的話，點這則通知立刻解除。電話、簡訊、設定不會被鎖。"
            ongoing = true
            onlyAlertOnce = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = unlock
        }
    }
}

/** 通知栏的「解除」：她随时可以自己解开 */
class AppLockUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppLock.ACTION_UNLOCK) AppLock.unlock(context, byHer = true)
    }
}
