package me.rerere.rikkahub.hoshio.anniversary

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import kotlin.uuid.Uuid

/**
 * 一个重要的日子。[date] 是 yyyy-MM-dd；[yearly] 时每年同一天都算（生日、纪念日），否则只有那一天。
 * [addedBy] 是 "ai" 或 "human"。
 */
@Serializable
data class Anniversary(
    val id: String = Uuid.random().toString().take(8),
    val title: String,
    val date: String,
    val yearly: Boolean = true,
    val note: String = "",
    val addedBy: String = "human",
) {
    val localDate: LocalDate? get() = runCatching { LocalDate.parse(date) }.getOrNull()

    /** 下一次是哪一天（今天也算）；非每年且已过去时回传 null */
    fun nextOccurrence(today: LocalDate = LocalDate.now()): LocalDate? {
        val base = localDate ?: return null
        if (!yearly) return base.takeIf { !it.isBefore(today) }
        val md = MonthDay.from(base)
        val thisYear = md.atYear(today.year)
        return if (thisYear.isBefore(today)) md.atYear(today.year + 1) else thisYear
    }

    fun isToday(today: LocalDate = LocalDate.now()): Boolean = nextOccurrence(today) == today

    /** 每年的日子：今年是第几年（例如在一起第 2 年） */
    fun yearsSince(today: LocalDate = LocalDate.now()): Long? {
        val base = localDate ?: return null
        if (!yearly) return null
        val next = nextOccurrence(today) ?: return null
        return ChronoUnit.YEARS.between(base, next).takeIf { it > 0 }
    }
}

/**
 * 星汐「纪念日」：重要的日子，人类和 AI 都能新增；也记录「认识的那一天」。
 */
object AnniversaryStore {
    private const val PREFS = "hoshio_anniversaries"
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow<List<Anniversary>>(emptyList())
    private var loaded = false

    val items: StateFlow<List<Anniversary>> = _items.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): List<Anniversary> {
        if (!loaded) {
            _items.value = prefs(context).getString("list", null)
                ?.let { runCatching { json.decodeFromString<List<Anniversary>>(it) }.getOrNull() }
                .orEmpty()
            loaded = true
        }
        return _items.value
    }

    @Synchronized
    private fun save(context: Context, list: List<Anniversary>) {
        _items.value = list
        prefs(context).edit { putString("list", json.encodeToString(list)) }
    }

    @Synchronized
    fun add(context: Context, item: Anniversary): Anniversary {
        require(item.localDate != null) { "date must be yyyy-MM-dd" }
        save(context, load(context) + item)
        return item
    }

    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val list = load(context)
        if (list.none { it.id == id }) return false
        save(context, list.filterNot { it.id == id })
        return true
    }

    /** 依下一次的日子排序 */
    fun upcoming(context: Context, today: LocalDate = LocalDate.now()): List<Pair<Anniversary, LocalDate>> =
        load(context).mapNotNull { a -> a.nextOccurrence(today)?.let { a to it } }.sortedBy { it.second }

    /** 认识的那一天；没设定时回传 null */
    fun metDate(context: Context): LocalDate? =
        prefs(context).getString("met_date", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun setMetDate(context: Context, date: LocalDate?) {
        prefs(context).edit { putString("met_date", date?.toString()) }
    }

    /** 当天让他来提醒或庆祝 */
    fun remindEnabled(context: Context): Boolean = prefs(context).getBoolean("remind_enabled", true)

    fun setRemindEnabled(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean("remind_enabled", value) }
    }

    /** 今天已经提醒过的日期 */
    fun notifiedFor(context: Context): String? = prefs(context).getString("notified_for", null)

    fun setNotifiedFor(context: Context, date: String) {
        prefs(context).edit { putString("notified_for", date) }
    }
}
