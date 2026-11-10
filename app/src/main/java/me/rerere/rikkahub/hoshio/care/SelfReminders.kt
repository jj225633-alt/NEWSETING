package me.rerere.rikkahub.hoshio.care

import android.content.Context
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** AI 自己约好下次来找她的时间，以及他留给自己的提示 */
@Serializable
data class SelfReminder(
    val id: String = Uuid.random().toString().take(8),
    val dueAt: Long,
    val note: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 星汐「自己约下次」：AI 可以替自己排定下一次主动来找她的时间。
 * 到时间时由 [CareWorker] 把他的提示放进最近的对话，让他主动开口。
 */
object SelfReminders {
    const val MIN_MINUTES = 5L
    const val MAX_MINUTES = 14L * 24 * 60
    const val MAX_PENDING = 5

    internal const val KEY_REMINDER_ID = "self_reminder_id"
    private const val PREFS = "hoshio_self_reminders"
    private const val KEY_LIST = "list"
    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<SelfReminder> =
        prefs(context).getString(KEY_LIST, null)
            ?.let { runCatching { json.decodeFromString<List<SelfReminder>>(it) }.getOrNull() }
            .orEmpty()
            .sortedBy { it.dueAt }

    @Synchronized
    private fun save(context: Context, list: List<SelfReminder>) {
        prefs(context).edit { putString(KEY_LIST, json.encodeToString(list)) }
    }

    @Synchronized
    fun add(context: Context, delayMinutes: Long, note: String): SelfReminder {
        val pending = all(context)
        require(pending.size < MAX_PENDING) { "You already have $MAX_PENDING scheduled check-ins; cancel one first." }
        val minutes = delayMinutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        val reminder = SelfReminder(dueAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes), note = note.take(500))
        save(context, pending + reminder)
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(reminder.id),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CareWorker>()
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_REMINDER_ID to reminder.id))
                .build(),
        )
        return reminder
    }

    @Synchronized
    fun cancel(context: Context, id: String): Boolean {
        val pending = all(context)
        if (pending.none { it.id == id }) return false
        save(context, pending.filterNot { it.id == id })
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        return true
    }

    /** 到期时取出并删除；已被取消就回传 null */
    @Synchronized
    fun take(context: Context, id: String): SelfReminder? {
        val pending = all(context)
        val reminder = pending.firstOrNull { it.id == id } ?: return null
        save(context, pending.filterNot { it.id == id })
        return reminder
    }

    private fun workName(id: String) = "hoshio_self_reminder_$id"
}
