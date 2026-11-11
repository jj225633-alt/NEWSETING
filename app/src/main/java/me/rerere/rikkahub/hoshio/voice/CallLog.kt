package me.rerere.rikkahub.hoshio.voice

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** 通話裡的一句話；他的話附上當時念出來的語音檔 */
@Serializable
data class CallLine(val at: Long, val him: Boolean, val text: String, val audio: List<String> = emptyList())

@Serializable
data class CallRecord(
    val id: String,
    val startedAt: Long,
    val endedAt: Long = 0,
    /** 他打來的原因；她打給他時為 null */
    val reason: String? = null,
    val lines: List<CallLine> = emptyList(),
    val starred: Boolean = false,
    /** 語音已經被自動清掉（文字還在） */
    val audioCleared: Boolean = false,
) {
    val durationSec: Long get() = ((if (endedAt > 0) endedAt else System.currentTimeMillis()) - startedAt) / 1000
}

/**
 * 星汐「通話紀錄」：每通電話的文字和他的語音，可以回放。
 * 語音只留最近 [KEEP_AUDIO_CALLS] 通、而且不超過 [KEEP_AUDIO_DAYS] 天；收藏的不刪。文字一直留著（最多 300 通）。
 */
object CallLog {
    const val KEEP_AUDIO_CALLS = 20
    const val KEEP_AUDIO_DAYS = 30L
    private const val PREFS = "hoshio_call_log"
    private val json = Json { ignoreUnknownKeys = true }
    private val _records = MutableStateFlow<List<CallRecord>>(emptyList())
    val records: StateFlow<List<CallRecord>> = _records.asStateFlow()
    private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun dir(context: Context, id: String) = File(context.filesDir, "hoshio_calls/$id").apply { mkdirs() }
    fun file(context: Context, id: String, name: String) = File(File(context.filesDir, "hoshio_calls/$id"), name)

    @Synchronized
    fun load(context: Context): List<CallRecord> {
        if (!loaded) {
            _records.value = prefs(context).getString("list", null)
                ?.let { runCatching { json.decodeFromString<List<CallRecord>>(it) }.getOrNull() }.orEmpty()
            loaded = true
        }
        return _records.value
    }

    @Synchronized
    private fun save(context: Context, list: List<CallRecord>) {
        val trimmed = list.sortedByDescending { it.startedAt }.take(300)
        _records.value = trimmed
        prefs(context).edit { putString("list", json.encodeToString(trimmed)) }
    }

    @Synchronized
    fun update(context: Context, id: String, fn: (CallRecord) -> CallRecord) {
        save(context, load(context).map { if (it.id == id) fn(it) else it })
    }

    fun start(context: Context, reason: String?): String {
        val id = "call_${System.currentTimeMillis()}"
        synchronized(this) { save(context, listOf(CallRecord(id = id, startedAt = System.currentTimeMillis(), reason = reason)) + load(context)) }
        return id
    }

    fun addLine(context: Context, id: String, line: CallLine) = update(context, id) { it.copy(lines = it.lines + line) }

    /** 掛斷：沒說到話的空通話直接刪掉；再清一次舊語音 */
    fun end(context: Context, id: String) {
        val record = load(context).firstOrNull { it.id == id } ?: return
        if (record.lines.isEmpty()) {
            dir(context, id).deleteRecursively()
            synchronized(this) { save(context, load(context).filterNot { it.id == id }) }
        } else {
            update(context, id) { it.copy(endedAt = System.currentTimeMillis()) }
        }
        cleanup(context)
    }

    fun setStarred(context: Context, id: String, starred: Boolean) = update(context, id) { it.copy(starred = starred) }

    fun delete(context: Context, id: String) {
        dir(context, id).deleteRecursively()
        synchronized(this) { save(context, load(context).filterNot { it.id == id }) }
    }

    /** 超過 20 通或 30 天的語音刪掉（收藏的留著），文字保留 */
    fun cleanup(context: Context) {
        val now = System.currentTimeMillis()
        val list = load(context)
        val keep = list.filter { !it.starred }.take(KEEP_AUDIO_CALLS).map { it.id }.toSet()
        list.forEach { r ->
            val old = now - r.startedAt > TimeUnit.DAYS.toMillis(KEEP_AUDIO_DAYS)
            if (!r.starred && !r.audioCleared && (r.id !in keep || old)) {
                File(context.filesDir, "hoshio_calls/${r.id}").deleteRecursively()
                update(context, r.id) { it.copy(audioCleared = true, lines = it.lines.map { l -> l.copy(audio = emptyList()) }) }
            }
        }
    }

    fun audioBytes(context: Context): Long =
        File(context.filesDir, "hoshio_calls").walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
