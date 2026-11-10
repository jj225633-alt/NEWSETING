package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.care.SelfReminders
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

/**
 * 星汐: 让 AI 自己决定下一次什么时候主动来找她。
 */
internal fun buildSelfReminderTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "schedule_next_contact",
        description = """
            Schedule a time for you to come back and start talking to her on your own, e.g. to check how something went,
            say good night, or simply because you want to. Give either minutes_from_now or at (local time "yyyy-MM-dd HH:mm").
            Allowed range: ${SelfReminders.MIN_MINUTES} minutes to 14 days; at most ${SelfReminders.MAX_PENDING} pending at once.
            The note is a private message to your future self about why you are coming back; she does not see it.
            When the time comes you will receive the note and can send her a message. Timing may be a few minutes late.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("minutes_from_now", buildJsonObject {
                        put("type", "integer")
                        put("description", "How many minutes from now")
                    })
                    put("at", buildJsonObject {
                        put("type", "string")
                        put("description", "Local time yyyy-MM-dd HH:mm (used if minutes_from_now is not given)")
                    })
                    put("note", buildJsonObject {
                        put("type", "string")
                        put("description", "Why you are coming back / what you want to say or check")
                    })
                },
                required = listOf("note"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val note = obj["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(note.isNotBlank()) { "note is required" }
            val minutes = obj["minutes_from_now"]?.jsonPrimitive?.longOrNull
                ?: obj["at"]?.jsonPrimitive?.contentOrNull?.let { at ->
                    val target = runCatching { LocalDateTime.parse(at.trim(), TIME_FORMAT) }.getOrNull()
                        ?: error("at must look like 2026-01-31 21:30")
                    Duration.between(LocalDateTime.now(), target).toMinutes()
                }
                ?: error("give minutes_from_now or at")
            require(minutes >= SelfReminders.MIN_MINUTES) { "too soon: at least ${SelfReminders.MIN_MINUTES} minutes from now" }
            require(minutes <= SelfReminders.MAX_MINUTES) { "too far: at most 14 days from now" }
            val reminder = SelfReminders.add(context, minutes, note)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("scheduled", true)
                put("id", reminder.id)
                put("time", formatTime(reminder.dueAt))
            }.toString()))
        }
    ),
    Tool(
        name = "list_scheduled_contacts",
        description = "List the times you have scheduled to come back and talk to her, with your notes.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val payload = buildJsonArray {
                SelfReminders.all(context).forEach { reminder ->
                    add(buildJsonObject {
                        put("id", reminder.id)
                        put("time", formatTime(reminder.dueAt))
                        put("note", reminder.note)
                    })
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "cancel_scheduled_contact",
        description = "Cancel one of your scheduled check-ins by id.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject { put("type", "string") })
                },
                required = listOf("id"),
            )
        },
        execute = { args ->
            val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val cancelled = SelfReminders.cancel(context, id)
            listOf(UIMessagePart.Text(buildJsonObject { put("cancelled", cancelled) }.toString()))
        }
    ),
)
