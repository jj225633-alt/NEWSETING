package me.rerere.rikkahub.hoshio.library

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import java.io.File
import kotlin.uuid.Uuid

/** 书房里的一本书。进度是「读到第几段」（从 1 开始，0 表示还没开始） */
@Serializable
data class Book(
    val id: String = Uuid.random().toString().take(8),
    val title: String,
    val partCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val humanProgress: Int = 0,
    val aiProgress: Int = 0,
)

/** 批注：谁在哪一段、针对哪句话写了什么 */
@Serializable
data class BookNote(
    val id: String = Uuid.random().toString().take(8),
    val part: Int,
    val quote: String = "",
    val note: String,
    val author: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 星汐「书房」：汇入没有 DRM 的 TXT、EPUB、PDF、DOCX，切成一段一段，两个人都能读、都能写批注。
 * 全部存在手机里。
 */
object LibraryStore {
    const val PART_CHARS = 2500
    const val AUTHOR_AI = "ai"
    const val AUTHOR_HUMAN = "human"

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    private val _notesVersion = MutableStateFlow(0)
    private var loaded = false

    val books: StateFlow<List<Book>> = _books.asStateFlow()

    /** 批注有变动时加一，让画面重新读取 */
    val notesVersion: StateFlow<Int> = _notesVersion.asStateFlow()

    private fun dir(context: Context) = File(context.filesDir, "hoshio/library").apply { mkdirs() }
    private fun indexFile(context: Context) = File(dir(context), "index.json")
    private fun safeId(id: String) = id.also { require(it.matches(Regex("[A-Za-z0-9-]{1,40}"))) { "invalid id" } }
    private fun partsFile(context: Context, id: String) = File(dir(context), "${safeId(id)}.parts.json")
    private fun notesFile(context: Context, id: String) = File(dir(context), "${safeId(id)}.notes.json")

    private suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        _books.value = withContext(Dispatchers.IO) {
            val f = indexFile(context)
            if (!f.exists()) emptyList() else runCatching { json.decodeFromString<List<Book>>(f.readText()) }.getOrDefault(emptyList())
        }.sortedByDescending { it.createdAt }
        loaded = true
    }

    private suspend fun saveIndex(context: Context, list: List<Book>) {
        _books.value = list.sortedByDescending { it.createdAt }
        withContext(Dispatchers.IO) { indexFile(context).writeText(json.encodeToString(_books.value)) }
    }

    suspend fun load(context: Context): List<Book> = mutex.withLock {
        ensureLoaded(context)
        _books.value
    }

    suspend fun get(context: Context, id: String): Book? = load(context).firstOrNull { it.id == id }

    /** 从档案汇入一本书 */
    suspend fun import(context: Context, uri: Uri, displayName: String): Book = withContext(Dispatchers.IO) {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val temp = File.createTempFile("book_", ".$ext", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: error("無法讀取檔案")
            val text = when (ext) {
                "epub" -> EpubParser.parse(temp)
                "pdf" -> PdfParser.parserPdf(temp)
                "docx" -> DocxParser.parse(temp)
                "txt", "md", "text" -> temp.readText()
                else -> error("不支援的格式：.$ext（可以用 TXT、EPUB、PDF、DOCX）")
            }
            val parts = splitIntoParts(text)
            require(parts.isNotEmpty()) { "這個檔案裡讀不到文字（可能有加密或是掃描圖片）" }
            mutex.withLock {
                ensureLoaded(context)
                val book = Book(title = displayName.substringBeforeLast('.').take(80), partCount = parts.size)
                partsFile(context, book.id).writeText(json.encodeToString(parts))
                saveIndex(context, _books.value + book)
                book
            }
        } finally {
            temp.delete()
        }
    }

    /** 依段落切成约 [PART_CHARS] 字一段，尽量不在段落中间切开 */
    private fun splitIntoParts(text: String): List<String> {
        val paragraphs = text.replace("\r\n", "\n").split(Regex("\n\\s*\n|\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (p in paragraphs) {
            if (p.length > PART_CHARS) {
                if (current.isNotEmpty()) { parts += current.toString(); current.clear() }
                p.chunked(PART_CHARS).forEach { parts += it }
                continue
            }
            if (current.length + p.length + 1 > PART_CHARS && current.isNotEmpty()) {
                parts += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(p)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    /** 读第几段（1 开始） */
    suspend fun readPart(context: Context, id: String, part: Int): String? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<List<String>>(partsFile(context, id).readText()) }.getOrNull()?.getOrNull(part - 1)
    }

    suspend fun setProgress(context: Context, id: String, author: String, part: Int) = mutex.withLock {
        ensureLoaded(context)
        saveIndex(context, _books.value.map {
            if (it.id != id) it
            else if (author == AUTHOR_AI) it.copy(aiProgress = part.coerceIn(0, it.partCount))
            else it.copy(humanProgress = part.coerceIn(0, it.partCount))
        })
    }

    suspend fun notes(context: Context, id: String): List<BookNote> = withContext(Dispatchers.IO) {
        val f = notesFile(context, id)
        if (!f.exists()) emptyList() else runCatching { json.decodeFromString<List<BookNote>>(f.readText()) }.getOrDefault(emptyList())
    }

    suspend fun addNote(context: Context, id: String, note: BookNote) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = notesFile(context, id)
            val list = if (!f.exists()) emptyList() else runCatching { json.decodeFromString<List<BookNote>>(f.readText()) }.getOrDefault(emptyList())
            f.writeText(json.encodeToString(list + note))
        }
        _notesVersion.value = _notesVersion.value + 1
    }

    suspend fun deleteNote(context: Context, id: String, noteId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = notesFile(context, id)
            if (f.exists()) {
                val list = runCatching { json.decodeFromString<List<BookNote>>(f.readText()) }.getOrDefault(emptyList())
                f.writeText(json.encodeToString(list.filterNot { it.id == noteId }))
            }
        }
        _notesVersion.value = _notesVersion.value + 1
    }

    suspend fun delete(context: Context, id: String) = mutex.withLock {
        ensureLoaded(context)
        withContext(Dispatchers.IO) {
            runCatching { partsFile(context, id).delete() }
            runCatching { notesFile(context, id).delete() }
        }
        saveIndex(context, _books.value.filterNot { it.id == id })
    }
}
