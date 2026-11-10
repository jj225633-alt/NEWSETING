package me.rerere.rikkahub.hoshio.backup

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "HoshioAutoBackup"
private const val PERIODIC_WORK = "hoshio_auto_backup"
private const val NOW_WORK = "hoshio_auto_backup_now"

/**
 * 星汐「自动备份」：定期把对话、设定、记忆和日记备份到「备份与还原」里设定好的 WebDAV 或 S3，
 * 只保留最近几份，避免占满空间。
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val webDavSync: WebDavSync by inject()
    private val s3Sync: S3Sync by inject()

    override suspend fun doWork(): Result {
        val prefs = AutoBackupPrefs(applicationContext)
        val settings = settingsStore.awaitLoaded()
        val webDav = settings.webDavConfig
        val s3 = settings.s3Config
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
        return runCatching {
            when {
                webDav.url.isNotBlank() -> {
                    webDavSync.backup(webDav)
                    webDavSync.listBackupFiles(webDav)
                        .sortedByDescending { it.lastModified }
                        .drop(prefs.keepCount)
                        .forEach { runCatching { webDavSync.deleteBackupFile(webDav, it) } }
                    "已備份到 WebDAV（$now）"
                }

                s3.endpoint.isNotBlank() && s3.bucket.isNotBlank() -> {
                    s3Sync.backupToS3(s3)
                    s3Sync.listBackupFiles(s3)
                        .sortedByDescending { it.lastModified }
                        .drop(prefs.keepCount)
                        .forEach { runCatching { s3Sync.deleteS3BackupFile(s3, it) } }
                    "已備份到 S3（$now）"
                }

                else -> "還沒設定 WebDAV 或 S3，請先到「備份與還原」設定"
            }
        }.fold(
            onSuccess = {
                prefs.lastResult = it
                Result.success()
            },
            onFailure = {
                Log.e(TAG, "auto backup failed", it)
                prefs.lastResult = "備份失敗（$now）：${it.message ?: it.javaClass.simpleName}"
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        fun sync(context: Context) {
            val prefs = AutoBackupPrefs(context)
            val workManager = WorkManager.getInstance(context)
            if (prefs.enabled) {
                workManager.enqueueUniquePeriodicWork(
                    PERIODIC_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<AutoBackupWorker>(prefs.intervalHours.toLong(), TimeUnit.HOURS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .setRequiresBatteryNotLow(true)
                                .build()
                        )
                        .build(),
                )
            } else {
                workManager.cancelUniqueWork(PERIODIC_WORK)
            }
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AutoBackupWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}

class AutoBackupPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hoshio_backup", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit { putBoolean("enabled", value) }

    /** 每隔几小时备份一次 */
    var intervalHours: Int
        get() = prefs.getInt("interval_hours", 24)
        set(value) = prefs.edit { putInt("interval_hours", value) }

    /** 远端最多保留几份 */
    var keepCount: Int
        get() = prefs.getInt("keep_count", 7)
        set(value) = prefs.edit { putInt("keep_count", value) }

    var lastResult: String?
        get() = prefs.getString("last_result", null)
        set(value) = prefs.edit { putString("last_result", value) }
}
