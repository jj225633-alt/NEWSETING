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
            Her daily care routines (drinking water, meals, medicine) that she set up in the app, and today's log.
            action=status to see the routines and what she has done today. action=log when she tells you she already did one
            (give routine_id) so the reminder will not fire again.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "status", "log"))
                    put("routine_id", tStr("log: which routine"))
                    put("note", tStr("log: optional note, e.g. 吃了牛肉麵"))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val routines = RoutineStore.load(context)
            if (arg("action") == "log") {
                val routine = routines.firstOrNull { it.id == arg("routine_id") }
                if (routine == null) {
                    tJson("error" to "Unknown routine_id", "routines" to routines.joinToString("; ") { "[${it.id}] ${it.label}" })
                } else {
                    RoutineLogStore.log(context, routine.id, BY_HIM, arg("note"))
                    tJson("ok" to "Logged ${routine.label}")
                }
            } else {
                val today = RoutineLogStore.today(context)
                tJson(
                    "routines" to routines.filter { it.enabled }.joinToString("; ") { r ->
                        "[${r.id}] ${r.label} at ${r.times.joinToString(",")}; done today at " +
                            today.filter { it.routineId == r.id }.joinToString(",") { it.hm().substringAfter(' ') }.ifEmpty { "-" }
                    }.ifEmpty { "She has not set up any routines (首頁 → 生活照顧)." },
                )
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
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", tEnum("What to do", "list", "view", "caption"))
                    put("photo_id", tStr("view / caption: which photo"))
                    put("text", tStr("caption: your caption"))
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
)
