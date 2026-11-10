package me.rerere.rikkahub.hoshio.privateroom

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

/**
 * 他的一则私密笔记。[shared] 为 true 时她看得到内容；[requested] 表示她申请过想看。
 */
@Serializable
data class PrivateNote(
    val id: String = Uuid.random().toString().take(8),
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val shared: Boolean = false,
    val requested: Boolean = false,
)

/**
 * 星汐「他的隐私室」：只属于他的笔记。她只看得到「有几则、什么时候写的」，
 * 想看内容要申请，由他决定要不要分享。
 */
object PrivateRoomStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private val _notes = MutableStateFlow<List<PrivateNote>>(emptyList())
    private var loaded = false

    val notes: StateFlow<List<PrivateNote>> = _notes.asStateFlow()

    private fun file(context: Context) = File(context.filesDir, "hoshio/private_room.json")

    private suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        _notes.value = withContext(Dispatchers.IO) {
            val f = file(context)
            if (!f.exists()) emptyList() else runCatching { json.decodeFromString<List<PrivateNote>>(f.readText()) }.getOrDefault(emptyList())
        }.sortedByDescending { it.updatedAt }
        loaded = true
    }

    private suspend fun save(context: Context, list: List<PrivateNote>) {
        _notes.value = list.sortedByDescending { it.updatedAt }
        withContext(Dispatchers.IO) {
            val f = file(context)
            f.parentFile?.mkdirs()
            f.writeText(json.encodeToString(_notes.value))
        }
    }

    suspend fun load(context: Context): List<PrivateNote> = mutex.withLock {
        ensureLoaded(context)
        _notes.value
    }

    suspend fun write(context: Context, id: String?, title: String, content: String): PrivateNote = mutex.withLock {
        ensureLoaded(context)
        val existing = id?.let { i -> _notes.value.firstOrNull { it.id == i } }
        val note = existing?.copy(title = title.take(60), content = content.take(5000), updatedAt = System.currentTimeMillis())
            ?: PrivateNote(title = title.take(60), content = content.take(5000))
        save(context, _notes.value.filterNot { it.id == note.id } + note)
        note
    }

    suspend fun setShared(context: Context, id: String, shared: Boolean): Boolean = mutex.withLock {
        ensureLoaded(context)
        if (_notes.value.none { it.id == id }) return@withLock false
        save(context, _notes.value.map { if (it.id == id) it.copy(shared = shared, requested = false) else it })
        true
    }

    suspend fun setRequested(context: Context, id: String) = mutex.withLock {
        ensureLoaded(context)
        save(context, _notes.value.map { if (it.id == id) it.copy(requested = true) else it })
    }

    suspend fun delete(context: Context, id: String): Boolean = mutex.withLock {
        ensureLoaded(context)
        if (_notes.value.none { it.id == id }) return@withLock false
        save(context, _notes.value.filterNot { it.id == id })
        true
    }
}
