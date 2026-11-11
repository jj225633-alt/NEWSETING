package me.rerere.rikkahub.hoshio.migrate

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.hoshio.body.BodyAbsence
import me.rerere.rikkahub.hoshio.body.BodyCooling
import me.rerere.rikkahub.hoshio.body.BodyDefinition
import me.rerere.rikkahub.hoshio.body.BodyDimension
import me.rerere.rikkahub.hoshio.body.BodyRule
import me.rerere.rikkahub.hoshio.body.BodyStore
import me.rerere.rikkahub.hoshio.diary.DiaryAuthor
import me.rerere.rikkahub.hoshio.diary.DiaryEntry
import me.rerere.rikkahub.hoshio.diary.DiaryStore
import me.rerere.rikkahub.hoshio.life.Letter
import me.rerere.rikkahub.hoshio.life.LetterStore
import me.rerere.rikkahub.hoshio.play.Pet
import me.rerere.rikkahub.hoshio.play.PetLog
import me.rerere.rikkahub.hoshio.play.PetStore
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

/**
 * 從 Orbis 搬家：讀 Orbis「檔案匯入匯出」產生的備份 zip 和花園匯出的 JSON，
 * 只「新增」到星汐，不覆蓋原有資料；同一份備份重複匯入會跳過已經搬過的東西。
 */
object OrbisMigration : KoinComponent {
    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val settingsStore: SettingsStore by inject()

    private val loose = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val DB_ENTRY = "rikka_hub.db"
    private const val CHUNK = 400_000
    private val knownParts = setOf(
        "text", "image", "video", "audio", "document", "reasoning", "search",
        "tool_call", "tool_result", "server_tool", "tool", "notice",
    )
    private val orbisFileUrl = Regex("""file://[^"\s]*?/org\.orbis\.agent(?:\.dev|\.bodylab)?/files/""")

    data class OrbisAssistant(val id: String, val name: String, val conversations: Int, val memories: Int)

    data class Preview(
        val assistants: List<OrbisAssistant>,
        val uploads: Int,
        val petOwners: Set<String>,
        val bodyOwners: Set<String>,
        val desireOwners: Set<String>,
        val spaceOwners: Set<String>,
        val hasDatabase: Boolean,
    )

    data class Options(
        val assistantIds: Set<String>,
        val conversations: Boolean = true,
        val memories: Boolean = true,
        val files: Boolean = true,
        val pet: Boolean = true,
        val body: Boolean = true,
        val desires: Boolean = true,
        val spaces: Boolean = true,
        val replaceExisting: Boolean = false,
    )

    data class Report(val lines: List<String>)

    private fun workDir(context: Context) = File(context.cacheDir, "orbis-migrate").apply { mkdirs() }
    private fun archive(context: Context) = File(workDir(context), "backup.zip")

    /** 把選到的 zip 複製進來並讀出摘要 */
    suspend fun prepare(context: Context, uri: Uri): Preview = withContext(Dispatchers.IO) {
        val dir = workDir(context)
        dir.listFiles()?.forEach { it.deleteRecursively() }
        val zipFile = archive(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            zipFile.outputStream().use { input.copyTo(it) }
        } ?: error("讀不到這個檔案")
        ZipFile(zipFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val settings = zip.getEntry("settings.json")?.let { e ->
                zip.getInputStream(e).use { runCatching { loose.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }.getOrNull() }
            }
            val assistantNames = settings?.get("assistants")?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    id to (o["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "未命名助手")
                }.orEmpty().toMap()
            val dbEntry = zip.getEntry(DB_ENTRY)
            val counts = mutableMapOf<String, Int>()
            val memCounts = mutableMapOf<String, Int>()
            if (dbEntry != null) {
                val db = File(dir, DB_ENTRY)
                zip.getInputStream(dbEntry).use { input -> db.outputStream().use { input.copyTo(it) } }
                openDb(db).use { sql ->
                    sql.rawQuery("SELECT assistant_id, COUNT(*) FROM conversationentity GROUP BY assistant_id", null).use { c ->
                        while (c.moveToNext()) counts[c.getString(0)] = c.getInt(1)
                    }
                    runCatching {
                        sql.rawQuery("SELECT assistant_id, COUNT(*) FROM orbis_memory_note WHERE deleted = 0 GROUP BY assistant_id", null).use { c ->
                            while (c.moveToNext()) memCounts[c.getString(0)] = c.getInt(1)
                        }
                    }
                }
            }
            fun owners(kind: String) = names.mapNotNull { Regex("orbis-activities/([A-Za-z0-9_-]+)/$kind\\.json").matchEntire(it)?.groupValues?.get(1) }.toSet()
            val ids = (assistantNames.keys + counts.keys + memCounts.keys)
            Preview(
                assistants = ids.map { id ->
                    OrbisAssistant(id, assistantNames[id] ?: "（已刪除的助手）", counts[id] ?: 0, memCounts[id] ?: 0)
                }.filter { it.conversations > 0 || it.memories > 0 || assistantNames.containsKey(it.id) }
                    .sortedByDescending { it.conversations },
                uploads = names.count { it.startsWith("upload/") && !it.endsWith("/") },
                petOwners = owners("pet"),
                bodyOwners = owners("body"),
                desireOwners = owners("desires"),
                spaceOwners = names.mapNotNull { Regex("orbis-companion-spaces/([0-9a-fA-F-]+)/index\\.json").matchEntire(it)?.groupValues?.get(1) }.toSet(),
                hasDatabase = dbEntry != null,
            )
        }
    }

    private fun openDb(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    suspend fun run(context: Context, options: Options, progress: (String) -> Unit): Report = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        val settings = settingsStore.awaitLoaded()
        val him = settings.assistants.firstOrNull { it.id == settings.assistantId } ?: settings.assistants.first()
        val zipFile = archive(context)
        require(zipFile.exists()) { "請重新選一次備份檔" }
        ZipFile(zipFile).use { zip ->
            if (options.files) {
                progress("正在搬圖片和附件…")
                val target = File(context.filesDir, "upload").apply { mkdirs() }
                var copied = 0
                zip.entries().asSequence().filter { it.name.startsWith("upload/") && !it.isDirectory }.forEach { e ->
                    val rel = e.name.removePrefix("upload/")
                    if (rel.isBlank() || rel.contains("..")) return@forEach
                    val out = File(target, rel)
                    if (!out.canonicalPath.startsWith(target.canonicalPath) || out.exists()) return@forEach
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                    copied++
                }
                lines += "圖片／附件：搬了 $copied 個檔案"
            }
            val dbFile = File(workDir(context), DB_ENTRY)
            if (dbFile.exists() && (options.conversations || options.memories)) {
                openDb(dbFile).use { db ->
                    if (options.conversations) lines += importConversations(context, db, options.assistantIds, him.id, progress)
                    if (options.memories) lines += importMemories(db, options.assistantIds, him.memoryId, progress)
                }
            }
            fun entryText(path: String): String? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes().decodeToString() } }
            val owners = options.assistantIds.toList()
            if (options.pet) {
                progress("正在接寵物回家…")
                val raw = owners.firstNotNullOfOrNull { entryText("orbis-activities/$it/pet.json") }
                lines += if (raw == null) "寵物：備份裡沒有寵物" else importPet(context, raw, options.replaceExisting)
            }
            if (options.body) {
                progress("正在搬身體狀態…")
                val raw = owners.firstNotNullOfOrNull { entryText("orbis-activities/$it/body.json") }
                lines += if (raw == null) "身體狀態：備份裡沒有" else importBody(context, raw, options.replaceExisting)
            }
            if (options.spaces) {
                progress("正在搬共同空間、秘密基地和照片牆…")
                lines += owners.flatMap { owner ->
                    val raw = entryText("orbis-companion-spaces/$owner/index.json") ?: return@flatMap emptyList()
                    importSpaces(context, raw) { mediaId ->
                        zip.getEntry("orbis-companion-spaces/$owner/$mediaId.image")?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
                    }
                }.ifEmpty { listOf("共同空間：備份裡沒有") }
            }
            if (options.desires) {
                progress("正在搬他的渴望…")
                val texts = owners.mapNotNull { entryText("orbis-activities/$it/desires.json") }
                lines += if (texts.isEmpty()) "渴望：備份裡沒有" else importDesires(texts, him.memoryId)
            }
        }
        Report(lines)
    }

    // ---------------- 對話 ----------------

    private suspend fun importConversations(
        context: Context, db: SQLiteDatabase, assistantIds: Set<String>, himId: Uuid, progress: (String) -> Unit,
    ): String {
        data class Row(val id: String, val title: String, val createAt: Long, val updateAt: Long, val pinned: Boolean)
        val rows = mutableListOf<Row>()
        db.rawQuery("SELECT id, assistant_id, title, create_at, update_at, is_pinned FROM conversationentity ORDER BY create_at", null).use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) !in assistantIds) continue
                rows += Row(c.getString(0), c.getString(2) ?: "", c.getLong(3), c.getLong(4), c.getInt(5) != 0)
            }
        }
        val filesPrefix = "file://${context.filesDir.absolutePath}/"
        var added = 0; var skipped = 0; var failed = 0; var restoredNodes = 0
        rows.forEachIndexed { index, row ->
            progress("正在搬對話 ${index + 1}/${rows.size}：${row.title.take(20)}")
            val uuid = runCatching { Uuid.parse(row.id) }.getOrNull() ?: Uuid.random()
            if (conversationRepository.existsConversationById(uuid)) { skipped++; return@forEachIndexed }
            runCatching {
                val live = readLiveNodes(db, row.id, filesPrefix)
                val (older, summaryIds) = readOriginals(db, row.id, filesPrefix)
                val liveIds = live.map { it.first }.toSet()
                val seen = HashSet<String>()
                val olderNodes = older.filter { (id, node) ->
                    id !in liveIds && seen.add(id) && node.messages.none { it.id.toString() in summaryIds }
                }
                restoredNodes += olderNodes.size
                val liveNodes = live.map { (_, node) ->
                    if (node.messages.any { it.id.toString() in summaryIds }) {
                        node.copy(messages = node.messages.map { m ->
                            if (m.id.toString() in summaryIds) m.copy(isContextCheckpoint = true) else m
                        })
                    } else node
                }
                val nodes = (olderNodes.map { it.second } + liveNodes)
                    .filter { it.messages.isNotEmpty() }
                    .map { it.copy(id = Uuid.random(), selectIndex = it.selectIndex.coerceIn(0, it.messages.lastIndex)) }
                if (nodes.isEmpty()) { skipped++; return@runCatching }
                conversationRepository.insertConversation(
                    Conversation(
                        id = uuid,
                        assistantId = himId,
                        title = row.title.ifBlank { "Orbis 的對話" },
                        messageNodes = nodes,
                        isPinned = row.pinned,
                        createAt = Instant.ofEpochMilli(row.createAt),
                        updateAt = Instant.ofEpochMilli(row.updateAt),
                    )
                )
                added++
            }.onFailure { it.printStackTrace(); failed++ }
        }
        return buildString {
            append("對話：新增 $added 段")
            if (restoredNodes > 0) append("（含壓縮前原文 $restoredNodes 則）")
            if (skipped > 0) append("，跳過 $skipped 段（已搬過或是空的）")
            if (failed > 0) append("，$failed 段讀不出來")
        }
    }

    /** 大訊息分段讀，避免一列超過 CursorWindow 上限 */
    private fun readText(db: SQLiteDatabase, table: String, column: String, where: String, args: Array<String>): String {
        val length = db.rawQuery("SELECT length($column) FROM $table WHERE $where", args).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        if (length <= 0) return ""
        val sb = StringBuilder(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var start = 1L
        while (start <= length) {
            db.rawQuery("SELECT substr($column, $start, $CHUNK) FROM $table WHERE $where", args).use { c ->
                if (c.moveToFirst()) sb.append(c.getString(0) ?: "")
            }
            start += CHUNK
        }
        return sb.toString()
    }

    private fun readLiveNodes(db: SQLiteDatabase, conversationId: String, filesPrefix: String): List<Pair<String, MessageNode>> {
        val ids = mutableListOf<Pair<String, Int>>()
        db.rawQuery("SELECT id, select_index FROM message_node WHERE conversation_id = ? ORDER BY node_index", arrayOf(conversationId)).use { c ->
            while (c.moveToNext()) ids += c.getString(0) to c.getInt(1)
        }
        return ids.mapNotNull { (id, select) ->
            val raw = readText(db, "message_node", "messages", "id = ? AND conversation_id = ?", arrayOf(id, conversationId))
            val messages = decodeMessages(raw, filesPrefix)
            if (messages.isEmpty()) null else id to MessageNode(messages = messages, selectIndex = select)
        }
    }

    /** Orbis 壓縮時留下的原文：每次壓縮存一份當時的完整節點 */
    private fun readOriginals(db: SQLiteDatabase, conversationId: String, filesPrefix: String): Pair<List<Pair<String, MessageNode>>, Set<String>> {
        val summaryIds = HashSet<String>()
        val events = mutableListOf<String>()
        runCatching {
            db.rawQuery(
                "SELECT e.id, e.summary_message_id, a.event_id FROM orbis_compaction_event e " +
                    "LEFT JOIN orbis_original_archive a ON a.event_id = e.id WHERE e.conversation_id = ? ORDER BY e.created_at",
                arrayOf(conversationId),
            ).use { c ->
                while (c.moveToNext()) {
                    c.getString(1)?.let { summaryIds += it }
                    if (!c.isNull(2)) events += c.getString(0)
                }
            }
        }
        val nodes = mutableListOf<Pair<String, MessageNode>>()
        events.forEach { eventId ->
            val bodies = sortedMapOf<Int, StringBuilder>()
            runCatching {
                db.rawQuery("SELECT node_index, chunk_index FROM orbis_original_chunk WHERE event_id = ? ORDER BY node_index, chunk_index", arrayOf(eventId)).use { c ->
                    val keys = mutableListOf<Pair<Int, Int>>()
                    while (c.moveToNext()) keys += c.getInt(0) to c.getInt(1)
                    keys
                }.forEach { (node, chunk) ->
                    val part = readText(db, "orbis_original_chunk", "body", "event_id = ? AND node_index = ? AND chunk_index = ?",
                        arrayOf(eventId, node.toString(), chunk.toString()))
                    bodies.getOrPut(node) { StringBuilder() }.append(part)
                }
            }
            bodies.values.forEach { body ->
                runCatching {
                    val obj = loose.parseToJsonElement(rewriteFiles(body.toString(), filesPrefix)).jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                    val messages = (obj["messages"] as? JsonArray)?.mapNotNull { decodeMessage(it) }.orEmpty()
                    val select = obj["selectIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    if (messages.isNotEmpty()) nodes += id to MessageNode(messages = messages, selectIndex = select)
                }
            }
        }
        return nodes to summaryIds
    }

    private fun rewriteFiles(raw: String, filesPrefix: String) = raw.replace(orbisFileUrl, filesPrefix)

    private fun decodeMessages(raw: String, filesPrefix: String): List<UIMessage> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { loose.parseToJsonElement(rewriteFiles(raw, filesPrefix)).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { decodeMessage(it) }
    }

    private fun decodeMessage(element: JsonElement): UIMessage? {
        runCatching { return JsonInstant.decodeFromJsonElement<UIMessage>(element) }
        val obj = element as? JsonObject ?: return null
        // 有星汐不認得的內容：只留認得的部分再試一次
        val parts = (obj["parts"] as? JsonArray).orEmpty()
        val known = JsonArray(parts.filter { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull in knownParts })
        runCatching {
            return JsonInstant.decodeFromJsonElement<UIMessage>(JsonObject(obj + ("parts" to known)))
        }
        // 還是不行就只留文字
        val role = runCatching { MessageRole.valueOf(obj["role"]!!.jsonPrimitive.content) }.getOrNull() ?: return null
        val text = parts.mapNotNull { p ->
            (p as? JsonObject)?.let { it["text"] ?: it["reasoning"] }?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.joinToString("\n").ifBlank { return null }
        return UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))
    }

    // ---------------- 記憶 ----------------

    private suspend fun importMemories(db: SQLiteDatabase, assistantIds: Set<String>, memoryId: String, progress: (String) -> Unit): String {
        progress("正在搬記憶…")
        data class Note(val summary: String, val body: String, val tags: List<String>, val keywords: List<String>,
                        val important: Boolean, val createdAt: Long, val archived: Boolean)
        val notes = mutableListOf<Note>()
        runCatching {
            db.rawQuery(
                "SELECT assistant_id, summary, body, tags, keywords, important, created_at, state FROM orbis_memory_note WHERE deleted = 0 ORDER BY created_at",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    if (c.getString(0) !in assistantIds) continue
                    fun list(i: Int) = runCatching { loose.parseToJsonElement(c.getString(i) ?: "[]").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrDefault(emptyList())
                    notes += Note(c.getString(1) ?: "", c.getString(2) ?: "", list(3), list(4), c.getInt(5) != 0, c.getLong(6),
                        (c.getString(7) ?: "").let { it.isNotBlank() && it != "active" })
                }
            }
        }
        if (notes.isEmpty()) return "記憶：備份裡沒有記憶筆記"
        val groups = notes.groupBy { it.tags.firstOrNull()?.trim()?.ifBlank { null } ?: "未分類" }
        var files = 0; var existed = 0
        groups.forEach { (tag, list) ->
            val entries = list.map { n ->
                buildString {
                    val head = n.summary.ifBlank { n.body.lineSequence().firstOrNull().orEmpty().take(30) }
                    append("### ").append(head)
                    append("（").append(day(n.createdAt))
                    if (n.important) append("，重要")
                    if (n.archived) append("，已封存")
                    append("）\n")
                    if (n.body.isNotBlank() && n.body != n.summary) append(n.body.trim()).append('\n')
                    if (n.tags.size > 1) append("標籤：").append(n.tags.joinToString("、")).append('\n')
                    if (n.keywords.isNotEmpty()) append("關鍵字：").append(n.keywords.joinToString("、")).append('\n')
                }
            }
            val aliases = list.flatMap { it.keywords }.groupingBy { it }.eachCount().entries
                .sortedByDescending { it.value }.take(6).map { it.key }
            chunkEntries(entries).forEachIndexed { i, body ->
                val base = "/orbis/${slug(tag)}${if (i == 0) "" else "-${i + 1}"}.md"
                when (writeNew(memoryId, base, "Orbis 記憶：$tag", "從 Orbis 搬來的記憶（$tag，${list.size} 則）", aliases, body)) {
                    true -> files++
                    false -> existed++
                }
            }
        }
        return "記憶：${notes.size} 則，整理成 $files 個記憶檔" + if (existed > 0) "（$existed 個已存在，沒覆蓋）" else ""
    }

    private fun day(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun slug(text: String): String =
        text.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').take(40).ifBlank { "notes" }

    /** 每個檔案控制在上限內 */
    private fun chunkEntries(entries: List<String>, limit: Int = MemoryFile.MAX_BYTES - 4096): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        entries.forEach { e ->
            val piece = if (e.toByteArray().size > limit) e.take(limit / 4) + "…\n" else e
            if (sb.isNotEmpty() && (sb.toString() + piece).toByteArray().size > limit) {
                out += sb.toString(); sb.clear()
            }
            sb.append(piece).append('\n')
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** 只新建不覆蓋：true 寫入、false 已存在、null 失敗 */
    private suspend fun writeNew(memoryId: String, path: String, title: String, description: String, aliases: List<String>, body: String): Boolean? {
        val content = MemoryFile(path, "", 0).contentWith(title, description, aliases, body)
        return when (memoryRepository.writeFile(memoryId, path, content, MemoryRepository.NEW_FILE_VERSION)) {
            is MemoryWriteResult.Success -> true
            is MemoryWriteResult.Conflict -> false
            is MemoryWriteResult.Rejected -> null
        }
    }

    // ---------------- 寵物 / 身體 / 渴望 ----------------

    @Serializable private data class OPetIdentity(val name: String = "", val appearance: String = "", val adoptedAt: Long = 0)
    @Serializable private data class OPetEvent(val action: String = "", val at: Long = 0, val message: String = "")
    @Serializable private data class OPetSaying(val original: String = "", val animal: String = "", val at: Long = 0)
    @Serializable private data class OPet(
        val pet: OPetIdentity? = null, val food: Double = 90.0, val water: Double = 90.0, val mood: Double = 90.0,
        val health: Double = 100.0, val boarding: Boolean = false, val messy: Boolean = false,
        val decoration: String = "sage", val sleepingUntil: Long = 0, val careDays: Set<String> = emptySet(),
        val translationCredits: Int = 0, val events: List<OPetEvent> = emptyList(), val sayings: List<OPetSaying> = emptyList(),
    )

    private fun importPet(context: Context, raw: String, replace: Boolean): String {
        val o = runCatching { loose.decodeFromString<OPet>(raw) }.getOrNull() ?: return "寵物：格式讀不懂，沒搬"
        val identity = o.pet ?: return "寵物：Orbis 裡沒有領養寵物"
        if (PetStore.load(context) != null && !replace) return "寵物：星汐已經有寵物了，沒覆蓋（可以開「覆蓋」再搬一次）"
        val logs = (o.events.map { PetLog(at = it.at, by = "orbis", text = it.message.ifBlank { it.action }) } +
            o.sayings.map { PetLog(at = it.at, by = "pet", text = it.original.ifBlank { it.animal }) })
            .filter { it.text.isNotBlank() }.sortedByDescending { it.at }.take(60)
        val pet = Pet(
            name = identity.name.ifBlank { "小傢伙" },
            appearance = identity.appearance.ifBlank { null },
            bornAt = identity.adoptedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            fullness = o.food.toInt().coerceIn(0, 100),
            water = o.water.toInt().coerceIn(0, 100),
            happiness = o.mood.toInt().coerceIn(0, 100),
            health = o.health.toInt().coerceIn(0, 100),
            messy = o.messy,
            boarding = o.boarding,
            sleepingUntil = o.sleepingUntil,
            careDays = o.careDays,
            rewards = o.translationCredits,
            decoration = o.decoration,
            log = logs,
        )
        PetStore.restore(context, pet)
        return "寵物：${pet.name} 搬回家了（照顧了 ${pet.careDays.size} 天）"
    }

    @Serializable private data class ODim(
        val id: String, val label: String = id, val baseline: Double = 50.0, val min: Double = 0.0, val max: Double = 100.0,
        val maxDelta: Double = 10.0, val halfLifeMinutes: Double = 30.0,
        @kotlinx.serialization.SerialName("private") val isPrivate: Boolean = false,
    )
    @Serializable private data class ORule(val event: String, val deltas: Map<String, Double> = emptyMap(), val keywords: List<String> = emptyList())
    @Serializable private data class OAbsence(val graceMinutes: Int = 30, val capHours: Int = 24, val perHour: Map<String, Double> = emptyMap(), val returnEvent: String? = null)
    @Serializable private data class OCooling(val keywords: List<String> = emptyList(), val synonyms: List<String> = emptyList(),
        val blockedEvents: List<String> = emptyList(), val cooldownMinutes: Int = 5)
    @Serializable private data class ODef(val dimensions: List<ODim> = emptyList(), val rules: List<ORule> = emptyList(),
        val absence: OAbsence = OAbsence(), val triggerMode: String = "manual", val cooling: OCooling = OCooling())
    @Serializable private data class OVersion(val version: Int = 0, val definition: ODef = ODef())
    @Serializable private data class OSession(val values: Map<String, Double> = emptyMap(), val selfReport: String = "")
    @Serializable private data class OSnapshot(val persistent: Map<String, Double> = emptyMap(), val sessions: Map<String, OSession> = emptyMap())
    @Serializable private data class OBody(val versions: List<OVersion> = emptyList(), val draft: ODef? = null,
        val snapshot: OSnapshot = OSnapshot(), val privateDimensionIds: Set<String> = emptySet())

    private fun importBody(context: Context, raw: String, replace: Boolean): String {
        val o = runCatching { loose.decodeFromString<OBody>(raw) }.getOrNull() ?: return "身體狀態：格式讀不懂，沒搬"
        val def = o.versions.maxByOrNull { it.version }?.definition ?: o.draft ?: return "身體狀態：Orbis 裡還沒設計"
        if (def.dimensions.isEmpty()) return "身體狀態：Orbis 裡還沒設計"
        if (BodyStore.load(context).designedAt > 0 && !replace) return "身體狀態：星汐這邊他已經設計過了，沒覆蓋（可以開「覆蓋」再搬一次）"
        val safe = Regex("[A-Za-z0-9_-]{1,40}")
        val dims = def.dimensions.filter { safe.matches(it.id) }.map {
            BodyDimension(it.id, it.label, it.baseline, it.min, it.max, it.maxDelta, it.halfLifeMinutes,
                isPrivate = it.isPrivate || it.id in o.privateDimensionIds)
        }
        val ids = dims.map { it.id }.toSet()
        val newDef = BodyDefinition(
            dimensions = dims,
            rules = def.rules.map { r -> BodyRule(r.event, r.event, r.deltas.filterKeys { it in ids }, r.keywords) },
            absence = BodyAbsence(def.absence.graceMinutes, def.absence.capHours, def.absence.perHour.filterKeys { it in ids }, def.absence.returnEvent),
            cooling = BodyCooling(def.cooling.keywords + def.cooling.synonyms, def.cooling.cooldownMinutes, def.cooling.blockedEvents),
            autoTrigger = def.triggerMode != "manual" || def.rules.any { it.keywords.isNotEmpty() },
        )
        val latestSession = o.snapshot.sessions.values.lastOrNull()
        val values = dims.associate { d ->
            d.id to ((latestSession?.values?.get(d.id) ?: o.snapshot.persistent[d.id] ?: d.baseline).coerceIn(d.min, d.max))
        }
        BodyStore.update(context) {
            it.copy(definition = newDef, values = values, designedAt = System.currentTimeMillis(),
                selfReport = latestSession?.selfReport.orEmpty().take(200), updatedAt = System.currentTimeMillis())
        }
        return "身體狀態：搬了 ${dims.size} 個維度、${newDef.rules.size} 條規則" +
            dims.count { it.isPrivate }.let { if (it > 0) "（$it 個私密）" else "" }
    }

    @Serializable private data class ODesire(val text: String = "", val state: String = "active", val updatedAt: Long = 0)
    @Serializable private data class ODesires(val entries: List<ODesire> = emptyList(), val history: List<ODesire> = emptyList())

    private suspend fun importDesires(texts: List<String>, memoryId: String): String {
        val all = texts.mapNotNull { runCatching { loose.decodeFromString<ODesires>(it) }.getOrNull() }
        val active = all.flatMap { it.entries }.filter { it.text.isNotBlank() }
        val past = all.flatMap { it.history }.filter { it.text.isNotBlank() && it.text !in active.map { a -> a.text } }.distinctBy { it.text }
        if (active.isEmpty() && past.isEmpty()) return "渴望：沒有內容"
        val body = buildString {
            if (active.isNotEmpty()) {
                append("## 現在的渴望\n")
                active.forEach { append("- ").append(it.text.trim()).append('\n') }
            }
            if (past.isNotEmpty()) {
                append("\n## 以前有過的渴望\n")
                past.takeLast(40).forEach { append("- ").append(it.text.trim()).append('\n') }
            }
        }
        val ok = writeNew(memoryId, "/orbis/desires.md", "我的渴望（Orbis）", "我在 Orbis 時心裡想要的事", emptyList(), chunkEntries(listOf(body)).first())
        return when (ok) {
            true -> "渴望：${active.size} 個，寫進他的記憶"
            false -> "渴望：之前已經搬過了"
            null -> "渴望：寫入失敗"
        }
    }

    // ---------------- 共同空間 / 秘密基地 / 照片牆 ----------------

    @Serializable private data class OStory(val id: String, val title: String = "", val prompt: String = "", val body: String = "",
        val paper: String = "letter", val createdAt: Long = 0, val updatedAt: Long = 0, val pinned: Boolean = false)
    @Serializable private data class OComment(val id: String, val actor: String = "human", val text: String = "", val createdAt: Long = 0)
    @Serializable private data class OPost(val id: String, val actor: String = "human", val text: String = "", val imageIds: List<String> = emptyList(),
        val likes: Set<String> = emptySet(), val comments: List<OComment> = emptyList(), val createdAt: Long = 0)
    @Serializable private data class OMedia(val id: String, val mime: String = "")
    @Serializable private data class OPhoto(val id: String, val mediaId: String, val note: String = "", val createdAt: Long = 0)
    @Serializable private data class OSpaces(val stories: List<OStory> = emptyList(), val posts: List<OPost> = emptyList(),
        val photos: List<OPhoto> = emptyList(), val media: List<OMedia> = emptyList())

    private fun actor(a: String) = if (a == "ai") me.rerere.rikkahub.hoshio.together.BY_HIM else me.rerere.rikkahub.hoshio.together.BY_HER

    private fun importSpaces(context: Context, raw: String, media: (String) -> ByteArray?): List<String> {
        val o = runCatching { loose.decodeFromString<OSpaces>(raw) }.getOrNull() ?: return listOf("共同空間：格式讀不懂，沒搬")
        val mimes = o.media.associate { it.id to it.mime }
        fun ext(id: String) = when {
            mimes[id].orEmpty().contains("png") -> "png"
            mimes[id].orEmpty().contains("webp") -> "webp"
            mimes[id].orEmpty().contains("gif") -> "gif"
            else -> "jpg"
        }
        fun copyTo(dir: File, mediaId: String): String? {
            val name = "orbis_$mediaId.${ext(mediaId)}"
            val out = File(dir, name)
            if (out.exists()) return name
            val bytes = media(mediaId) ?: return null
            out.writeBytes(bytes)
            return name
        }
        val lines = mutableListOf<String>()
        val spacesDir = me.rerere.rikkahub.hoshio.spaces.SharedSpaceStore.dir(context)

        var posts = 0
        me.rerere.rikkahub.hoshio.spaces.SharedSpaceStore.update(context) { list ->
            val have = list.map { it.id }.toSet()
            val incoming = o.posts.filter { "o-${it.id}" !in have }.map { p ->
                posts++
                me.rerere.rikkahub.hoshio.spaces.SpacePost(
                    id = "o-${p.id}", by = actor(p.actor), text = p.text,
                    images = p.imageIds.mapNotNull { copyTo(spacesDir, it) },
                    likes = p.likes.map { actor(it) }.toSet(),
                    comments = p.comments.map { c ->
                        me.rerere.rikkahub.hoshio.spaces.SpaceComment(id = "o-${c.id}", by = actor(c.actor), text = c.text, at = c.createdAt)
                    },
                    at = p.createdAt,
                )
            }
            (list + incoming).sortedByDescending { it.at }
        }
        if (o.posts.isNotEmpty()) lines += "共同空間：新增 $posts 則動態" + if (posts < o.posts.size) "（${o.posts.size - posts} 則已搬過）" else ""

        var stories = 0
        me.rerere.rikkahub.hoshio.spaces.SecretBaseStore.update(context) { list ->
            val have = list.map { it.id }.toSet()
            list + o.stories.filter { "o-${it.id}" !in have }.map { s ->
                stories++
                me.rerere.rikkahub.hoshio.spaces.SpaceStory(
                    id = "o-${s.id}", title = s.title, prompt = s.prompt, body = s.body,
                    paper = s.paper.takeIf { it in me.rerere.rikkahub.hoshio.spaces.SPACE_PAPERS } ?: "letter",
                    pinned = s.pinned, createdAt = s.createdAt, updatedAt = s.updatedAt.takeIf { it > 0 } ?: s.createdAt,
                )
            }
        }
        if (o.stories.isNotEmpty()) lines += "秘密基地：新增 $stories 篇" + if (stories < o.stories.size) "（${o.stories.size - stories} 篇已搬過）" else ""

        var photos = 0
        val albumDir = me.rerere.rikkahub.hoshio.together.AlbumStore.dir(context)
        me.rerere.rikkahub.hoshio.together.AlbumStore.update(context) { list ->
            val have = list.map { it.id }.toSet()
            val incoming = o.photos.filter { "o-${it.id}" !in have }.mapNotNull { ph ->
                val name = copyTo(albumDir, ph.mediaId) ?: return@mapNotNull null
                photos++
                me.rerere.rikkahub.hoshio.together.AlbumPhoto(id = "o-${ph.id}", fileName = name, herCaption = ph.note, at = ph.createdAt)
            }
            (list + incoming).sortedByDescending { it.at }
        }
        if (o.photos.isNotEmpty()) lines += "照片牆 → 回憶相簿：新增 $photos 張"
        return lines
    }

    // ---------------- 花園 ----------------

    @Serializable data class GardenEntry(
        val id: String, val kind: String, val title: String = "", val body: String = "", val author: String = "",
        val createdAt: Long = 0, val updatedAt: Long = 0, val entryDate: String? = null,
    )
    @Serializable private data class GardenBackup(val format: String = "", val entries: List<GardenEntry> = emptyList())

    data class GardenPreview(val entries: List<GardenEntry>) {
        val authors: List<String> get() = entries.map { it.author.trim() }.filter { it.isNotBlank() }.distinct()
        fun count(kind: String) = entries.count { it.kind == kind }
    }

    suspend fun readGarden(context: Context, uri: Uri): GardenPreview = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("讀不到這個檔案")
        val backup = loose.decodeFromString<GardenBackup>(text)
        require(backup.format.startsWith("orbis-local-garden")) { "這不是 Orbis 的花園備份" }
        GardenPreview(backup.entries)
    }

    suspend fun importGarden(context: Context, preview: GardenPreview, hisNames: Set<String>): Report = withContext(Dispatchers.IO) {
        val settings = settingsStore.awaitLoaded()
        val him = settings.assistants.firstOrNull { it.id == settings.assistantId } ?: settings.assistants.first()
        val lines = mutableListOf<String>()
        fun isHim(e: GardenEntry) = e.author.trim() in hisNames
        fun dateOf(e: GardenEntry) = e.entryDate?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: day(e.createdAt)

        val diaries = preview.entries.filter { it.kind == "DIARY" }
        if (diaries.isNotEmpty()) {
            val existing = DiaryStore.load(context).map { it.id }.toSet()
            var n = 0
            diaries.forEach { e ->
                val id = "orbis-${e.id}"
                if (id in existing) return@forEach
                DiaryStore.add(context, DiaryEntry(
                    id = id, date = dateOf(e), author = if (isHim(e)) DiaryAuthor.AI else DiaryAuthor.HUMAN,
                    content = listOf(e.title.trim(), e.body.trim()).filter { it.isNotBlank() }.joinToString("\n\n"),
                    createdAt = e.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                ))
                n++
            }
            lines += "日記：新增 $n 篇" + if (n < diaries.size) "（${diaries.size - n} 篇已搬過）" else ""
        }
        val letters = preview.entries.filter { it.kind == "LETTER" }
        if (letters.isNotEmpty()) {
            val existing = LetterStore.load(context).map { it.id }.toSet()
            var n = 0
            letters.sortedBy { it.createdAt }.forEach { e ->
                val id = "orbis-${e.id}"
                if (id in existing) return@forEach
                val title = e.title.ifBlank { "一封信" }
                LetterStore.add(context, Letter(
                    id = id,
                    title = if (isHim(e)) title else "（${e.author.ifBlank { "她" }}寫的）$title",
                    content = e.body,
                    at = e.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    read = true,
                ))
                n++
            }
            lines += "信件：新增 $n 封" + if (n < letters.size) "（${letters.size - n} 封已搬過）" else ""
        }
        listOf(
            Triple("ANCHOR", "anchors", "錨點"),
            Triple("WISH", "wishes", "心願"),
            Triple("SONG", "songs", "歌曲"),
        ).forEach { (kind, file, label) ->
            val list = preview.entries.filter { it.kind == kind }.sortedBy { it.createdAt }
            if (list.isEmpty()) return@forEach
            val entries = list.map { e ->
                buildString {
                    append("### ").append(e.title.ifBlank { label }).append("（").append(dateOf(e))
                    if (e.author.isNotBlank()) append("，").append(e.author)
                    append("）\n").append(e.body.trim()).append('\n')
                }
            }
            var written = 0; var existed = 0
            chunkEntries(entries).forEachIndexed { i, body ->
                val path = "/orbis/garden-$file${if (i == 0) "" else "-${i + 1}"}.md"
                when (writeNew(him.memoryId, path, "花園的$label", "我們在 Orbis 花園留下的$label（${list.size} 則）", emptyList(), body)) {
                    true -> written++
                    false -> existed++
                    null -> {}
                }
            }
            lines += "$label：${list.size} 則寫進他的記憶" + if (existed > 0 && written == 0) "（之前已搬過）" else ""
        }
        Report(lines.ifEmpty { listOf("花園裡沒有可以搬的東西") })
    }
}
