package me.rerere.rikkahub.hoshio.life

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private val lifeJson = Json { ignoreUnknownKeys = true }

/** 他现在的状态：心情（他自己的话）、精神 0–100、为什么 */
@Serializable
data class HoshioMood(
    val mood: String = "平靜",
    val energy: Int = 70,
    val reason: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 星汐「他的状态」：心情由他自己更新；精神会随时间慢慢回到 70（休息够了会恢复，太亢奋也会平复）。
 */
object MoodStore {
    private const val PREFS = "hoshio_life"
    private const val NEUTRAL_ENERGY = 70

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取时套用时间造成的变化 */
    fun current(context: Context): HoshioMood {
        val stored = prefs(context).getString("mood", null)
            ?.let { runCatching { lifeJson.decodeFromString<HoshioMood>(it) }.getOrNull() }
            ?: HoshioMood()
        val hours = (System.currentTimeMillis() - stored.updatedAt) / TimeUnit.HOURS.toMillis(1).toDouble()
        // 每过一小时，往 70 靠近约 15%
        val factor = Math.pow(0.85, hours.coerceAtLeast(0.0))
        val energy = (NEUTRAL_ENERGY + (stored.energy - NEUTRAL_ENERGY) * factor).roundToInt().coerceIn(0, 100)
        return stored.copy(energy = energy)
    }

    fun update(context: Context, mood: String?, energy: Int?, reason: String?): HoshioMood {
        val now = current(context)
        val updated = HoshioMood(
            mood = mood?.take(30)?.ifBlank { null } ?: now.mood,
            energy = (energy ?: now.energy).coerceIn(0, 100),
            reason = reason?.take(120) ?: now.reason,
        )
        prefs(context).edit { putString("mood", lifeJson.encodeToString(updated)) }
        return updated
    }

    /** 给模型看的一行状态 */
    fun describe(context: Context): String {
        val m = current(context)
        val minutes = (System.currentTimeMillis() - m.updatedAt) / 60_000
        return "mood: ${m.mood}; energy: ${m.energy}/100" +
            (if (m.reason.isNotBlank()) "; because: ${m.reason}" else "") +
            "; last updated ${minutes} min ago"
    }
}

/** 一次被唤醒的纪录：什么时候、被什么唤醒、他选择做了什么 */
@Serializable
data class WakeRecord(
    val id: String = Uuid.random().toString().take(8),
    val at: Long = System.currentTimeMillis(),
    val trigger: String,
    /** talked | silent | tools | failed */
    val action: String,
    val note: String = "",
)

/**
 * 星汐「他的作息」：每次被自动唤醒，他是跟她说话、继续睡、还是做了别的事，都留下纪录。
 */
object WakeLogStore {
    private const val PREFS = "hoshio_life"
    private const val MAX = 200
    private val _records = MutableStateFlow<List<WakeRecord>>(emptyList())
    private var loaded = false

    val records: StateFlow<List<WakeRecord>> = _records.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): List<WakeRecord> {
        if (!loaded) {
            _records.value = prefs(context).getString("wake_log", null)
                ?.let { runCatching { lifeJson.decodeFromString<List<WakeRecord>>(it) }.getOrNull() }
                .orEmpty()
            loaded = true
        }
        return _records.value
    }

    @Synchronized
    fun add(context: Context, record: WakeRecord) {
        val list = (listOf(record) + load(context)).take(MAX)
        _records.value = list
        prefs(context).edit { putString("wake_log", lifeJson.encodeToString(list)) }
    }
}

/** 他写给她的一封信 */
@Serializable
data class Letter(
    val id: String = Uuid.random().toString().take(8),
    val title: String,
    val content: String,
    val at: Long = System.currentTimeMillis(),
    val read: Boolean = false,
)

/**
 * 星汐「信箱」：他偶尔写给她的长信，跟日记分开放。
 */
object LetterStore {
    private const val PREFS = "hoshio_letters"
    private val _letters = MutableStateFlow<List<Letter>>(emptyList())
    private var loaded = false

    val letters: StateFlow<List<Letter>> = _letters.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): List<Letter> {
        if (!loaded) {
            _letters.value = prefs(context).getString("list", null)
                ?.let { runCatching { lifeJson.decodeFromString<List<Letter>>(it) }.getOrNull() }
                .orEmpty()
            loaded = true
        }
        return _letters.value
    }

    @Synchronized
    private fun save(context: Context, list: List<Letter>) {
        _letters.value = list
        prefs(context).edit { putString("list", lifeJson.encodeToString(list)) }
    }

    @Synchronized
    fun add(context: Context, letter: Letter): Letter {
        save(context, listOf(letter) + load(context))
        return letter
    }

    @Synchronized
    fun markRead(context: Context, id: String) {
        save(context, load(context).map { if (it.id == id) it.copy(read = true) else it })
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun unreadCount(context: Context): Int = load(context).count { !it.read }
}
