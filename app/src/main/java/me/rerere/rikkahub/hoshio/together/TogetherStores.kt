package me.rerere.rikkahub.hoshio.together

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.uuid.Uuid

private fun newId() = Uuid.random().toString().take(8)

const val BY_HER = "her"
const val BY_HIM = "him"

// ───────── 生活照顾：喝水、吃饭、吃药 ─────────

@Serializable
data class Routine(
    val id: String = newId(),
    /** water | meal | medicine | custom */
    val kind: String,
    val label: String,
    /** 每天的时间 "HH:mm" */
    val times: List<String>,
    val enabled: Boolean = true,
) {
    val emoji: String get() = when (kind) {
        "water" -> "💧"
        "meal" -> "🍚"
        "medicine" -> "💊"
        else -> "⏰"
    }
}

@Serializable
data class RoutineLog(val routineId: String, val at: Long = System.currentTimeMillis(), val by: String = BY_HER, val note: String = "")

object RoutineStore : JsonListStore<Routine>("hoshio_routines", Routine.serializer())

object RoutineLogStore : JsonListStore<RoutineLog>("hoshio_routine_logs", RoutineLog.serializer(), limit = 300) {
    fun log(context: Context, routineId: String, by: String, note: String = "") =
        update(context) { listOf(RoutineLog(routineId, by = by, note = note.take(60))) + it }

    fun today(context: Context): List<RoutineLog> {
        val start = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return load(context).filter { it.at >= start }
    }

    /** 某个提醒时间点（今天）有没有被做过：提醒前 60 分钟起算 */
    fun doneFor(context: Context, routineId: String, time: LocalTime): Boolean {
        val from = LocalDateTime.of(LocalDate.now(), time).minusMinutes(60)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return load(context).any { it.routineId == routineId && it.at >= from }
    }

    fun remindedKey(context: Context, key: String): Boolean = prefs(context).getBoolean("reminded_$key", false)
    fun markReminded(context: Context, key: String) = prefs(context).edit {
        // 只保留今天的纪录
        val today = LocalDate.now().toString()
        prefs(context).all.keys.filter { it.startsWith("reminded_") && !it.contains(today) }.forEach { remove(it) }
        putBoolean("reminded_$key", true)
    }
}

// ───────── 心情打卡 ─────────

@Serializable
data class MoodCheckin(val date: String, val emoji: String, val label: String, val note: String = "", val at: Long = System.currentTimeMillis())

object MoodCheckinStore : JsonListStore<MoodCheckin>("hoshio_mood_checkins", MoodCheckin.serializer(), limit = 800) {
    val MOODS = listOf(
        "😄" to "超開心", "😊" to "開心", "🥰" to "被愛著", "😌" to "平靜",
        "😴" to "好累", "😕" to "有點悶", "😢" to "難過", "😠" to "生氣", "😰" to "焦慮", "🤒" to "不舒服",
    )

    fun checkIn(context: Context, emoji: String, label: String, note: String, date: LocalDate = LocalDate.now()) =
        update(context) { list -> listOf(MoodCheckin(date.toString(), emoji, label, note.take(200))) + list.filterNot { it.date == date.toString() } }
}

// ───────── 回忆相簿 ─────────

@Serializable
data class AlbumPhoto(
    val id: String = newId(),
    val fileName: String,
    val herCaption: String = "",
    val hisCaption: String = "",
    val at: Long = System.currentTimeMillis(),
)

object AlbumStore : JsonListStore<AlbumPhoto>("hoshio_album", AlbumPhoto.serializer(), limit = 1000) {
    fun dir(context: Context) = File(context.filesDir, "hoshio_album").apply { mkdirs() }
    fun file(context: Context, photo: AlbumPhoto) = File(dir(context), photo.fileName)

    fun delete(context: Context, id: String) = update(context) { list ->
        list.firstOrNull { it.id == id }?.let { file(context, it).delete() }
        list.filterNot { it.id == id }
    }

    fun setCaption(context: Context, id: String, his: String? = null, her: String? = null) = update(context) { list ->
        list.map { if (it.id == id) it.copy(hisCaption = his?.take(200) ?: it.hisCaption, herCaption = her?.take(200) ?: it.herCaption) else it }
    }
}

// ───────── 时光胶囊 ─────────

@Serializable
data class Capsule(
    val id: String = newId(),
    val by: String,
    val title: String,
    val content: String,
    /** yyyy-MM-dd，当天起可以打开 */
    val openOn: String,
    val createdAt: Long = System.currentTimeMillis(),
    val opened: Boolean = false,
    val announced: Boolean = false,
) {
    fun unlocked(today: LocalDate = LocalDate.now()) = runCatching { !LocalDate.parse(openOn).isAfter(today) }.getOrDefault(true)
}

object CapsuleStore : JsonListStore<Capsule>("hoshio_capsules", Capsule.serializer())

// ───────── 我们的约定 ─────────

@Serializable
data class OurPromise(
    val id: String = newId(),
    val text: String,
    val by: String,
    /** yyyy-MM-dd，可以没有 */
    val due: String? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val reminded: Boolean = false,
)

object PromiseStore : JsonListStore<OurPromise>("hoshio_promises", OurPromise.serializer())

// ───────── 每日一问 ─────────

@Serializable
data class DailyQuestion(
    val id: String = newId(),
    val date: String = LocalDate.now().toString(),
    val asker: String,
    val question: String,
    val herAnswer: String = "",
    val hisAnswer: String = "",
)

object DailyQuestionStore : JsonListStore<DailyQuestion>("hoshio_daily_questions", DailyQuestion.serializer(), limit = 1000)

// ───────── 晚安模式 ─────────

object SleepMode {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("hoshio_sleep", Context.MODE_PRIVATE)

    fun until(context: Context): Long = prefs(context).getLong("until", 0L)

    fun isOn(context: Context): Boolean = System.currentTimeMillis() < until(context)

    /** 开始晚安模式：最晚到隔天早上 10 点自动结束 */
    fun start(context: Context) {
        val now = LocalDateTime.now()
        val end = (if (now.hour < 5) now.toLocalDate() else now.toLocalDate().plusDays(1)).atTime(10, 0)
        prefs(context).edit { putLong("until", end.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) }
    }

    fun stop(context: Context) = prefs(context).edit { putLong("until", 0L) }
}
