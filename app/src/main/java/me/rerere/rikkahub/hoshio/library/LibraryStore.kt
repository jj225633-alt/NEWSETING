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
    /** 汇入时自动转成繁体（台湾用字） */
    fun convertOnImport(context: Context): Boolean =
        context.getSharedPreferences("hoshio_library", Context.MODE_PRIVATE).getBoolean("convert_traditional", true)

    fun setConvertOnImport(context: Context, value: Boolean) {
        context.getSharedPreferences("hoshio_library", Context.MODE_PRIVATE).edit().putBoolean("convert_traditional", value).apply()
    }

    /** 把已经汇入的书转成繁体（书名与内容；批注不动） */
    suspend fun convertToTraditional(context: Context, id: String) = withContext(Dispatchers.IO) {
        val parts = runCatching { json.decodeFromString<List<String>>(partsFile(context, id).readText()) }.getOrNull()
            ?: error("讀不到這本書")
        val converted = parts.map { ChineseConverter.toTraditional(context, it) }
        mutex.withLock {
            ensureLoaded(context)
            partsFile(context, id).writeText(json.encodeToString(converted))
            saveIndex(context, _books.value.map { if (it.id == id) it.copy(title = ChineseConverter.toTraditional(context, it.title)) else it })
        }
    }

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
                "txt", "md", "text" -> decodeText(temp.readBytes())
                else -> error("不支援的格式：.$ext（可以用 TXT、EPUB、PDF、DOCX）")
            }
            val convert = convertOnImport(context)
            val parts = splitIntoParts(if (convert) ChineseConverter.toTraditional(context, text) else text)
            require(parts.isNotEmpty()) { "這個檔案裡讀不到文字（可能有加密或是掃描圖片）" }
            mutex.withLock {
                ensureLoaded(context)
                val rawTitle = displayName.substringBeforeLast('.').take(80)
                val title = if (convert) ChineseConverter.toTraditional(context, rawTitle) else rawTitle
                val book = Book(title = title, partCount = parts.size)
                partsFile(context, book.id).writeText(json.encodeToString(parts))
                saveIndex(context, _books.value + book)
                book
            }
        } finally {
            temp.delete()
        }
    }

    /**
     * 文字档的编码不一定是 UTF-8：网路小说常见 GBK（GB18030）或 Big5。
     * 依序看 BOM，再用严格模式试 UTF-8、GB18030、Big5，第一个完全解得开的就用它。
     */
    internal fun decodeText(bytes: ByteArray): String {
        fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        when {
            startsWith(0xEF, 0xBB, 0xBF) -> return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        fun strict(name: String): String? = runCatching {
            java.nio.charset.Charset.forName(name).newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        strict("UTF-8")?.let { return it }
        // GBK 几乎能解开任何 Big5 档案（只是变成乱码），所以两个都试，看哪个常用字比较多
        val gb = strict("GB18030")
        val big5 = strict("Big5")
        if (gb != null && big5 != null) {
            val common = "的一是了不在人有我他你她們们這这個个說说來来會会就也都和"
            fun score(text: String) = text.take(20_000).count { it in common }
            return if (score(big5) > score(gb)) big5 else gb
        }
        (gb ?: big5)?.let { return it }
        // 都解不开时用 GB18030 宽松解码（至少大部分字是对的）
        return String(bytes, java.nio.charset.Charset.forName("GB18030"))
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
