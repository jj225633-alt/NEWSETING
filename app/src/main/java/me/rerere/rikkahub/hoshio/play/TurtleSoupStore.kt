package me.rerere.rikkahub.hoshio.play

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private val soupJson = Json { ignoreUnknownKeys = true }

/** 一碗海龟汤。host = "him"（他出题）或 "her"（她出题） */
@Serializable
data class TurtleSoup(
    val id: String = Uuid.random().toString().take(8),
    val title: String,
    val surface: String,
    val answer: String,
    val host: String,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    /** solved | gave_up */
    val result: String? = null,
    val questions: Int = 0,
    val note: String = "",
)

/**
 * 星汐「海龟汤」：谜面公开、汤底藏起来。
 * 他出题时汤底先锁在这里，他不能中途改答案；她出题时他只看得到谜面。
 */
object TurtleSoupStore {
    private const val PREFS = "hoshio_turtle_soup"
    private val _games = MutableStateFlow<List<TurtleSoup>>(emptyList())
    val games: StateFlow<List<TurtleSoup>> = _games.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<TurtleSoup> {
        val list = prefs(context).getString("games", null)
            ?.let { runCatching { soupJson.decodeFromString<List<TurtleSoup>>(it) }.getOrNull() }
            .orEmpty()
        _games.value = list
        return list
    }

    private fun save(context: Context, list: List<TurtleSoup>) {
        val trimmed = list.take(50)
        prefs(context).edit { putString("games", soupJson.encodeToString(trimmed)) }
        _games.value = trimmed
    }

    fun current(context: Context): TurtleSoup? = load(context).firstOrNull { it.endedAt == null }

    /** 开新的一局；还没结束的那局会被当作放弃 */
    fun start(context: Context, title: String, surface: String, answer: String, host: String): TurtleSoup {
        val now = System.currentTimeMillis()
        val list = load(context).map { if (it.endedAt == null) it.copy(endedAt = now, result = "gave_up") else it }
        val game = TurtleSoup(title = title.take(40).ifBlank { "無題" }, surface = surface.take(2000), answer = answer.take(4000), host = host)
        save(context, listOf(game) + list)
        return game
    }

    fun countQuestion(context: Context): TurtleSoup? {
        val list = load(context)
        val game = list.firstOrNull { it.endedAt == null } ?: return null
        val updated = game.copy(questions = game.questions + 1)
        save(context, list.map { if (it.id == game.id) updated else it })
        return updated
    }

    fun end(context: Context, solved: Boolean, note: String = ""): TurtleSoup? {
        val list = load(context)
        val game = list.firstOrNull { it.endedAt == null } ?: return null
        val updated = game.copy(endedAt = System.currentTimeMillis(), result = if (solved) "solved" else "gave_up", note = note.take(300))
        save(context, list.map { if (it.id == game.id) updated else it })
        return updated
    }

    fun delete(context: Context, id: String) = save(context, load(context).filterNot { it.id == id })
}
