package me.rerere.rikkahub.hoshio.care

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import java.util.concurrent.TimeUnit

/**
 * 星汐「常驻模式」：用前台服务让程式一直醒着，每 [CHECK_INTERVAL_MINUTES] 分钟检查一次，
 * 比 WorkManager 最短 15 分钟的周期即时得多；代价是通知栏常驻一个通知、比较耗电。
 */
class CareForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !CarePrefs(this).alwaysOnEnabled) {
            stopSelfCompat()
            return START_NOT_STICKY
        }
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    if (CarePrefs(this@CareForegroundService).anyEnabled) {
                        CareWorker.checkNow(this@CareForegroundService)
                    }
                    delay(TimeUnit.MINUTES.toMillis(CHECK_INTERVAL_MINUTES))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopSelfCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(): Boolean {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("星汐常駐")
                .setDescription("常駐模式開啟時顯示，讓他更即時地關心你")
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build()
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle("他在陪著你")
            .setContentText("每 $CHECK_INTERVAL_MINUTES 分鐘留意一次手機狀況和位置")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(launch)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start care foreground service", e)
            CarePrefs(this).lastResult = "常駐模式啟動失敗：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    companion object {
        private const val TAG = "HoshioCareService"
        private const val CHANNEL_ID = "hoshio_care_always_on"
        private const val NOTIFICATION_ID = 7302
        private const val ACTION_STOP = "app.hoshio.care.STOP"
        const val CHECK_INTERVAL_MINUTES = 2L

        /** 依设定启动或停止常驻模式。从背景启动可能被系统拒绝，失败时不影响 15 分钟的一般检查 */
        fun sync(context: Context) {
            val prefs = CarePrefs(context)
            val intent = Intent(context, CareForegroundService::class.java)
            runCatching {
                if (prefs.alwaysOnEnabled && prefs.anyEnabled) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent.setAction(ACTION_STOP))
                }
            }.onFailure { Log.w(TAG, "sync failed", it) }
        }
    }
}
