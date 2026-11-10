package me.rerere.rikkahub.hoshio.playroom

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

/** 游戏室里的一件作品 */
@Serializable
data class PlayroomItem(
    val id: String = Uuid.random().toString().take(8),
    val title: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
)

/**
 * 星汐「游戏室」：他写的小游戏、小网页、小卡片，每件作品是一个独立的 HTML 档案，存在手机里。
 */
object PlayroomStore {
    const val MAX_HTML_CHARS = 200_000

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<PlayroomItem>>(emptyList())
    private var loaded = false

    val items: StateFlow<List<PlayroomItem>> = _items.asStateFlow()

    private fun dir(context: Context) = File(context.filesDir, "hoshio/playroom").apply { mkdirs() }
    private fun indexFile(context: Context) = File(dir(context), "index.json")
    private fun htmlFile(context: Context, id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]{1,40}"))) { "invalid id" }
        return File(dir(context), "$id.html")
    }

    private fun List<PlayroomItem>.sorted() =
        sortedWith(compareByDescending<PlayroomItem> { it.pinned }.thenByDescending { it.updatedAt })

    private suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        _items.value = withContext(Dispatchers.IO) {
            val f = indexFile(context)
            if (!f.exists()) emptyList() else runCatching { json.decodeFromString<List<PlayroomItem>>(f.readText()) }.getOrDefault(emptyList())
        }.sorted()
        loaded = true
    }

    private suspend fun saveIndex(context: Context, list: List<PlayroomItem>) {
        _items.value = list.sorted()
        withContext(Dispatchers.IO) { indexFile(context).writeText(json.encodeToString(_items.value)) }
    }

    suspend fun load(context: Context): List<PlayroomItem> = mutex.withLock {
        ensureLoaded(context)
        _items.value
    }

    suspend fun get(context: Context, id: String): PlayroomItem? = load(context).firstOrNull { it.id == id }

    suspend fun readHtml(context: Context, id: String): String? = withContext(Dispatchers.IO) {
        runCatching { htmlFile(context, id).takeIf { it.exists() }?.readText() }.getOrNull()
    }

    suspend fun create(context: Context, title: String, description: String, html: String): PlayroomItem = mutex.withLock {
        ensureLoaded(context)
        require(html.length <= MAX_HTML_CHARS) { "html is too long (max $MAX_HTML_CHARS characters)" }
        val item = PlayroomItem(title = title.take(60), description = description.take(200))
        withContext(Dispatchers.IO) { htmlFile(context, item.id).writeText(html) }
        saveIndex(context, _items.value + item)
        item
    }

    suspend fun update(context: Context, id: String, html: String?, title: String?, description: String?): PlayroomItem =
        mutex.withLock {
            ensureLoaded(context)
            val current = _items.value.firstOrNull { it.id == id } ?: error("no item with id $id")
            if (html != null) {
                require(html.length <= MAX_HTML_CHARS) { "html is too long (max $MAX_HTML_CHARS characters)" }
                withContext(Dispatchers.IO) { htmlFile(context, id).writeText(html) }
            }
            val updated = current.copy(
                title = title?.take(60) ?: current.title,
                description = description?.take(200) ?: current.description,
                updatedAt = System.currentTimeMillis(),
            )
            saveIndex(context, _items.value.map { if (it.id == id) updated else it })
            updated
        }

    suspend fun setPinned(context: Context, id: String, pinned: Boolean) = mutex.withLock {
        ensureLoaded(context)
        saveIndex(context, _items.value.map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    suspend fun delete(context: Context, id: String) = mutex.withLock {
        ensureLoaded(context)
        withContext(Dispatchers.IO) { runCatching { htmlFile(context, id).delete() } }
        saveIndex(context, _items.value.filterNot { it.id == id })
    }
}
