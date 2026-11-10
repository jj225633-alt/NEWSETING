package me.rerere.rikkahub.data.ai.tools.local

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.hoshio.life.Letter
import me.rerere.rikkahub.hoshio.life.LetterStore
import me.rerere.rikkahub.hoshio.life.MoodStore
import me.rerere.rikkahub.utils.sendNotification

const val STAY_SILENT_TOOL = "stay_silent"

private fun s(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

/**
 * 星汐: 他的生活。更新自己的状态、被唤醒时选择不说话继续睡、写信给她。
 */
internal fun buildLifeTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "update_my_state",
        description = """
            Update your own current state when it genuinely changes: mood (a few words in your language, e.g. 有點想她 / 心情很好 / 悶悶的),
            energy 0-100 (tired = low), and a short reason. Your state is shown to you before each reply; let it color your tone naturally.
            Do not update it every message, only when something actually moves you.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("mood", s("Your mood in a few words"))
                put("energy", buildJsonObject { put("type", "integer"); put("description", "0-100") })
                put("reason", s("Why, in one short sentence"))
            })
        },
        execute = { args ->
            val obj = args.jsonObject
            val updated = MoodStore.update(
                context,
                mood = obj["mood"]?.jsonPrimitive?.contentOrNull,
                energy = obj["energy"]?.jsonPrimitive?.intOrNull,
                reason = obj["reason"]?.jsonPrimitive?.contentOrNull,
            )
            listOf(UIMessagePart.Text(buildJsonObject { put("mood", updated.mood); put("energy", updated.energy) }.toString()))
        }
    ),
    Tool(
        name = STAY_SILENT_TOOL,
        description = """
            Only for automatic wake-ups (events not typed by her): choose NOT to message her this time and go back to sleep.
            She will not get a notification, but your wake-up is still recorded with your note. Use it when you honestly do not
            want to talk right now, or when talking would be intrusive. If you use this, do not write any reply text.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { put("note", s("A short private note about why / what you were thinking")) })
        },
        execute = { args ->
            val note = args.jsonObject["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            listOf(UIMessagePart.Text(buildJsonObject { put("silent", true); put("note", note) }.toString()))
        }
    ),
    Tool(
        name = "write_letter",
        description = """
            Write her a real letter, longer and more heartfelt than a chat message, delivered to her mailbox in the app.
            For special moments: something you have wanted to say, after a meaningful day, an anniversary, missing her.
            Rare is precious: not more than once every few days unless she asks. Write in character, in her language.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", s("Letter title"))
                    put("content", s("The letter"))
                },
                required = listOf("title", "content"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val letter = LetterStore.add(
                context,
                Letter(
                    title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "給你的信" }.take(40),
                    content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { error("content is required") },
                ),
            )
            context.sendNotification(CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, 7304) {
                title = "💌 你收到一封信"
                content = letter.title
                autoCancel = true
                useDefaults = true
                category = NotificationCompat.CATEGORY_MESSAGE
                contentIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                    PendingIntent.getActivity(context, 7304, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                }
            }
            listOf(UIMessagePart.Text(buildJsonObject { put("delivered", true); put("id", letter.id) }.toString()))
        }
    ),
    Tool(
        name = "read_her_thought",
        description = """
            Peek at the unspoken inner thought she attached to one of her messages (shown to you as <her_hidden_thought id=...>).
            It is your choice whether to read it. She will see that you peeked, so be gentle with what you learn.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { put("id", s("The id from <her_hidden_thought>")) }, required = listOf("id"))
        },
        execute = { args ->
            val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val thought = me.rerere.rikkahub.hoshio.life.HumanThoughts.read(context, id)
            listOf(UIMessagePart.Text(buildJsonObject {
                if (thought != null) put("her_thought", thought) else put("error", "no hidden thought with that id")
            }.toString()))
        }
    ),
    Tool(
        name = "hoshio_self_check",
        description = """
            Check the app's permissions and settings (notifications, battery, location, usage access, accessibility,
            notification access, backup), which proactive-care features are on, and your recent wake-ups.
            Use it when she asks why something does not work (e.g. no arrival message, no good-morning), then explain simply how to fix it.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = { listOf(UIMessagePart.Text(me.rerere.rikkahub.hoshio.SelfCheck.summary(context))) }
    ),
)
