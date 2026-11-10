package me.rerere.rikkahub.hoshio.diary

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.uuid.Uuid

/** 日记的作者 */
object DiaryAuthor {
    const val AI = "ai"
    const val HUMAN = "human"
}

/**
 * 一篇日记。[date] 是这篇写的是哪一天（yyyy-MM-dd），[visibleToAi] 只对人类写的日记有意义：
 * 关掉时 AI 读不到这篇。
 */
@Serializable
data class DiaryEntry(
    val id: String = Uuid.random().toString(),
    val date: String,
    val author: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val visibleToAi: Boolean = true,
)

/**
 * 星汐的日记本，整本存成手机里的一个 JSON 档案（只在本机，不会上传）。
 */
object DiaryStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<DiaryEntry>>(emptyList())
    private var loaded = false

    /** 依日期新到旧、同一天依写的时间新到旧 */
    val entries: StateFlow<List<DiaryEntry>> = _entries.asStateFlow()

    private fun file(context: Context) = File(context.filesDir, "hoshio/diary.json")

    private suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        val list = withContext(Dispatchers.IO) {
            val f = file(context)
            if (!f.exists()) emptyList() else runCatching { json.decodeFromString<List<DiaryEntry>>(f.readText()) }.getOrDefault(emptyList())
        }
        _entries.value = list.sorted()
        loaded = true
    }

    private fun List<DiaryEntry>.sorted() = sortedWith(compareByDescending<DiaryEntry> { it.date }.thenByDescending { it.createdAt })

    private suspend fun save(context: Context, list: List<DiaryEntry>) {
        _entries.value = list.sorted()
        withContext(Dispatchers.IO) {
            val f = file(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "diary.json.tmp")
            tmp.writeText(json.encodeToString(_entries.value))
            tmp.renameTo(f)
        }
    }

    suspend fun load(context: Context): List<DiaryEntry> = mutex.withLock {
        ensureLoaded(context)
        _entries.value
    }

    suspend fun add(context: Context, entry: DiaryEntry) = mutex.withLock {
        ensureLoaded(context)
        save(context, _entries.value + entry)
    }

    suspend fun update(context: Context, entry: DiaryEntry) = mutex.withLock {
        ensureLoaded(context)
        save(context, _entries.value.map { if (it.id == entry.id) entry else it })
    }

    suspend fun delete(context: Context, id: String) = mutex.withLock {
        ensureLoaded(context)
        save(context, _entries.value.filterNot { it.id == id })
    }

    /** AI 能读到的日记：他自己写的，以及人类允许他看的 */
    suspend fun visibleToAi(context: Context): List<DiaryEntry> =
        load(context).filter { it.author == DiaryAuthor.AI || it.visibleToAi }
}
