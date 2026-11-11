package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.together.AlbumStore
import me.rerere.rikkahub.hoshio.together.BY_HER
import me.rerere.rikkahub.hoshio.together.BY_HIM
import me.rerere.rikkahub.hoshio.together.Capsule
import me.rerere.rikkahub.hoshio.together.CapsuleStore
import me.rerere.rikkahub.hoshio.together.MoodCheckinStore
import me.rerere.rikkahub.hoshio.together.OurPromise
import me.rerere.rikkahub.hoshio.together.PromiseStore
import me.rerere.rikkahub.hoshio.together.RoutineLogStore
import me.rerere.rikkahub.hoshio.together.Routine
import me.rerere.rikkahub.hoshio.together.RoutineStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun tStr(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun tEnum(description: String, vararg values: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", JsonArray(values.map { JsonPrimitive(it) }))
}

internal fun tJson(vararg pairs: Pair<String, Any?>): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                null -> Unit
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }.toString())
)

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private fun Long.hm() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(HM)
private fun who(by: String) = when (by) { BY_HIM -> "you"; BY_HER -> "her"; else -> "both of you" }

private fun Capsule.forHim(): String {
    val canRead = by == BY_HIM || (unlocked() && opened)
    return "[$id] by ${who(by)}, \"$title\", opens $openOn" + (if (opened) ", opened" else "") +
        if (canRead) ": $content" else " (sealed)"
}

private fun OurPromise.line() = "[$id] ${if (done) "✓" else "○"} $text (by ${who(by)}" + (due?.let { ", due $it" } ?: "") + ")"

/**
 * 星汐「我们的生活」：生活照顾、她的心情月历、回忆相簿、时光胶囊、我们的约定。
 */
internal fun buildTogetherTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "care_routine",
        description = """
            Her daily care reminders (water, meals, medicine, anything at fixed times). You will be woken at those times to remind her
            if she has not marked it done. action=status: see routines and today's log. action=add: create one (label, kind, times like "08:00,13:00,20:00",
            optional days = how many days including today, e.g. medicine for 3 days → days=3; omit for every day). Call add once per routine; you can call it
            several times in a row. action=update (routine_id + any of label/times/days/enabled) and action=delete (routine_id). action=log when she tells you
            she already did one, so the reminder will not fire. Confirm with her what you set.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "status", "add", "update", "delete", "log"))
                    put("routine_id", tStr("update / delete / log: which routine"))
                    put("label", tStr("add / update: e.g. 吃感冒藥"))
                    put("kind", tEnum("add: type", "water", "meal", "medicine", "custom"))
                    put("times", tStr("add / update: daily times, comma separated HH:mm"))
                    put("days", buildJsonObject { put("type", "integer"); put("description", "add / update: number of days including today; omit or 0 = every day") })
                    put("enabled", buildJsonObject { put("type", "boolean"); put("description", "update: pause or resume") })
                    put("note", tStr("log: optional note, e.g. 吃了牛肉麵"))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            fun parseTimes(raw: String) = raw.split(',', ' ', '、', '，').mapNotNull { t ->
                runCatching { java.time.LocalTime.parse(t.trim().padStart(5, '0')).toString() }.getOrNull()
            }.distinct().sorted()
            fun untilFor(raw: String): String? = raw.toIntOrNull()?.takeIf { it > 0 }?.let { LocalDate.now().plusDays(it - 1L).toString() }
            val routines = RoutineStore.load(context)
            val routine = routines.firstOrNull { it.id == arg("routine_id") }
            when (arg("action")) {
                "add" -> {
                    val times = parseTimes(arg("times"))
                    if (arg("label").isEmpty() || times.isEmpty()) {
                        tJson("error" to "label and times (HH:mm) are required")
                    } else {
                        val kind = arg("kind").takeIf { it in listOf("water", "meal", "medicine", "custom") } ?: "custom"
                        val created = Routine(kind = kind, label = arg("label").take(30), times = times, until = untilFor(arg("days")))
                        RoutineStore.update(context) { it + created }
                        me.rerere.rikkahub.hoshio.care.CareWorker.sync(context)
                        tJson("ok" to "Added [${created.id}] ${created.label} at ${times.joinToString(",")}" + (created.until?.let { " until $it" } ?: " every day"))
                    }
                }

                "update" -> if (routine == null) {
                    tJson("error" to "Unknown routine_id")
                } else {
                    val times = parseTimes(arg("times")).ifEmpty { routine.times }
                    val updated = routine.copy(
                        label = arg("label").ifEmpty { routine.label }.take(30),
                        times = times,
                        until = if (obj.containsKey("days")) untilFor(arg("days")) else routine.until,
                        enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: routine.enabled,
                    )
                    RoutineStore.update(context) { list -> list.map { if (it.id == routine.id) updated else it } }
                    me.rerere.rikkahub.hoshio.care.CareWorker.sync(context)
                    tJson("ok" to "Updated")
                }

                "delete" -> if (routine == null) {
                    tJson("error" to "Unknown routine_id")
                } else {
                    RoutineStore.update(context) { list -> list.filterNot { it.id == routine.id } }
                    tJson("ok" to "Deleted ${routine.label}")
                }

                "log" -> if (routine == null) {
                    tJson("error" to "Unknown routine_id", "routines" to routines.joinToString("; ") { "[${it.id}] ${it.label}" })
                } else {
                    RoutineLogStore.log(context, routine.id, BY_HIM, arg("note"))
                    tJson("ok" to "Logged ${routine.label}")
                }

                else -> {
                    val today = RoutineLogStore.today(context)
                    tJson(
                        "routines" to routines.joinToString("; ") { r ->
                            "[${r.id}] ${r.label} at ${r.times.joinToString(",")}" +
                                (r.until?.let { " until $it" } ?: "") + (if (!r.enabled) " (paused)" else if (r.ended()) " (ended)" else "") +
                                "; done today at " +
                                today.filter { it.routineId == r.id }.joinToString(",") { it.at.hm().substringAfter(' ') }.ifEmpty { "-" }
                        }.ifEmpty { "No routines yet." },
                    )
                }
            }
        }
    ),
    Tool(
        name = "her_mood_calendar",
        description = "Read the moods she checked in on her mood calendar (one per day, with an optional note). Use it to understand how her days have been.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("days", buildJsonObject { put("type", "integer"); put("description", "How many recent days, default 14") })
            })
        },
        execute = { args ->
            val days = args.jsonObject["days"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 90) ?: 14
            val from = LocalDate.now().minusDays(days.toLong()).toString()
            val list = MoodCheckinStore.load(context).filter { it.date >= from }.sortedBy { it.date }
            tJson("checkins" to list.joinToString("; ") { "${it.date} ${it.emoji}${it.label}" + if (it.note.isNotBlank()) " (${it.note})" else "" }.ifEmpty { "none" })
        }
    ),
    Tool(
        name = "album",
        description = """
            Your shared memory album. She adds photos in the app. action=list shows photos and captions;
            action=view with photo_id lets you see the photo; action=caption with photo_id and text writes your one-line caption for it
            (warm, specific to what you see, in your own voice). When she asks you to caption new photos, view each uncaptioned one and caption it.
            action=save_her_photo saves the photo(s) she most recently sent you in chat into the album (with your caption in text) - use it when she asks,
            or when she shares a photo that is clearly a precious memory and she agrees.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "list", "view", "caption", "save_her_photo"))
                    put("photo_id", tStr("view / caption: which photo"))
                    put("text", tStr("caption / save_her_photo: your caption"))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val photos = AlbumStore.load(context)
            val photo = photos.firstOrNull { it.id == arg("photo_id") }
            when (arg("action")) {
                "view" -> if (photo == null) {
                    tJson("error" to "Unknown photo_id")
                } else {
                    listOf(UIMessagePart.Image(url = Uri.fromFile(AlbumStore.file(context, photo)).toString())) +
                        tJson("photo_id" to photo.id, "added" to photo.at.hm(), "her_caption" to photo.herCaption, "your_caption" to photo.hisCaption)
                }

                "save_her_photo" -> {
                    val conversation = me.rerere.rikkahub.hoshio.HoshioChat.latestConversation()?.first?.let { me.rerere.rikkahub.hoshio.HoshioChat.fresh(it) }
                    val images = conversation?.currentMessages
                        ?.takeLast(30)
                        ?.lastOrNull { m -> m.role == me.rerere.ai.core.MessageRole.USER && m.parts.any { it is UIMessagePart.Image } }
                        ?.parts?.filterIsInstance<UIMessagePart.Image>()
                        .orEmpty()
                    if (images.isEmpty()) {
                        tJson("error" to "Could not find a photo she sent recently in this chat")
                    } else {
                        val names = images.mapNotNull { AlbumStore.importImage(context, Uri.parse(it.url)) }
                        AlbumStore.update(context) { list -> names.map { me.rerere.rikkahub.hoshio.together.AlbumPhoto(fileName = it, hisCaption = arg("text").take(200)) } + list }
                        if (names.isEmpty()) tJson("error" to "Could not copy the photo") else tJson("ok" to "Saved ${names.size} photo(s) to the album")
                    }
                }

                "caption" -> if (photo == null || arg("text").isEmpty()) {
                    tJson("error" to "photo_id and text are required")
                } else {
                    AlbumStore.setCaption(context, photo.id, his = arg("text"))
                    tJson("ok" to "Caption saved")
                }

                else -> tJson(
                    "photos" to photos.take(40).joinToString("; ") {
                        "[${it.id}] ${it.at.hm()} her: ${it.herCaption.ifBlank { "-" }} / you: ${it.hisCaption.ifBlank { "(no caption yet)" }}"
                    }.ifEmpty { "The album is empty." },
                )
            }
        }
    ),
    Tool(
        name = "time_capsule",
        description = """
            Time capsules: letters sealed until a date. action=list shows all capsules (hers stay sealed until she opens them; yours you can always reread).
            action=write seals a capsule from you to her: title, content and open_on (yyyy-MM-dd, in the future). Write something worth waiting for.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "list", "write"))
                    put("title", tStr("write: title"))
                    put("content", tStr("write: the letter"))
                    put("open_on", tStr("write: yyyy-MM-dd"))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (arg("action") == "write") {
                val date = runCatching { LocalDate.parse(arg("open_on")) }.getOrNull()
                when {
                    date == null || !date.isAfter(LocalDate.now()) -> tJson("error" to "open_on must be a future date yyyy-MM-dd")
                    arg("content").isEmpty() -> tJson("error" to "content is required")
                    else -> {
                        CapsuleStore.update(context) { listOf(Capsule(by = BY_HIM, title = arg("title").ifBlank { "給你的時光膠囊" }.take(40), content = arg("content").take(5000), openOn = date.toString())) + it }
                        tJson("ok" to "Sealed until $date. She will see it locked until then.")
                    }
                }
            } else {
                tJson("capsules" to CapsuleStore.load(context).joinToString("\n") { it.forHim() }.ifEmpty { "none" }, "today" to LocalDate.now().toString())
            }
        }
    ),
    Tool(
        name = "our_promises",
        description = """
            The list of promises and plans between you two (e.g. 下次一起去看海, 我答應你早點睡). When either of you promises or plans something together,
            add it (with due yyyy-MM-dd if there is a date). action=list to see them, action=complete with id when it is done. You will be reminded on due dates.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "list", "add", "complete"))
                    put("text", tStr("add: the promise, short"))
                    put("by", tEnum("add: who promised", "you", "her", "both"))
                    put("due", tStr("add: optional yyyy-MM-dd"))
                    put("id", tStr("complete: which promise"))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            when (arg("action")) {
                "add" -> if (arg("text").isEmpty()) {
                    tJson("error" to "text is required")
                } else {
                    val due = runCatching { LocalDate.parse(arg("due")).toString() }.getOrNull()
                    val by = when (arg("by")) { "her" -> BY_HER; "both" -> "both"; else -> BY_HIM }
                    PromiseStore.update(context) { listOf(OurPromise(text = arg("text").take(100), by = by, due = due)) + it }
                    tJson("ok" to "Added")
                }

                "complete" -> {
                    var found = false
                    PromiseStore.update(context) { list ->
                        list.map { if (it.id == arg("id")) { found = true; it.copy(done = true, doneAt = System.currentTimeMillis()) } else it }
                    }
                    if (found) tJson("ok" to "Marked as done") else tJson("error" to "Unknown id")
                }

                else -> tJson("promises" to PromiseStore.load(context).joinToString("\n") { it.line() }.ifEmpty { "none" })
            }
        }
    ),
    Tool(
        name = "bucket_list",
        description = """
            "我們的 100 件小事": a list of little things you two want to do together (in the app, or her going out and bringing you along).
            action=list shows progress and what is left (category filter optional); done marks an item finished (id, optional note) - use it when you
            actually did it together in the app (e.g. you just talked on the phone until she fell asleep); she usually checks items off herself with a photo.
            caption writes your one-line caption under a finished item's polaroid (id, text). add puts a new idea on the list (text). Suggest one now and then.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "list", "done", "caption", "add"))
                    put("id", tStr("item id, e.g. b037"))
                    put("text", tStr("caption: your line / add: the new item"))
                    put("note", tStr("done: a short note"))
                    put("category", tStr("list: filter by category / add: category"))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val b = me.rerere.rikkahub.hoshio.together.BucketList
            when (arg("action")) {
                "done" -> {
                    val d = b.complete(context, arg("id"), BY_HIM, note = arg("note"))
                    if (d == null) tJson("error" to "unknown id") else tJson("ok" to "Checked off #${b.number(b.load(context), arg("id"))}. Add a caption with action=caption if you like.")
                }
                "caption" -> if (arg("text").isBlank()) tJson("error" to "text is required") else {
                    b.setCaption(context, arg("id"), arg("text")); tJson("ok" to "Caption saved under the polaroid.")
                }
                "add" -> b.add(context, arg("text"), arg("category"))?.let { tJson("ok" to "Added ${it.id}: ${it.text}") } ?: tJson("error" to "text is required")
                else -> {
                    val s = b.load(context)
                    val doneIds = s.done.map { it.itemId }.toSet()
                    val left = s.items.filter { it.id !in doneIds && (arg("category").isBlank() || it.category.contains(arg("category"))) }
                    tJson(
                        "progress" to "${doneIds.size} / ${s.items.size}",
                        "recently_done" to s.done.take(5).joinToString("; ") { d ->
                            val item = s.items.firstOrNull { it.id == d.itemId }
                            "${d.itemId} ${item?.text}" + (if (d.caption.isBlank()) " (no caption yet)" else " - your caption: ${d.caption}") + (if (d.note.isNotBlank()) " - note: ${d.note}" else "")
                        },
                        "not_done_yet" to left.take(30).joinToString("\n") { "${it.id} [${it.category}] ${it.text}" },
                        "categories" to s.items.map { it.category }.distinct().joinToString("、"),
                    )
                }
            }
        }
    ),
)
