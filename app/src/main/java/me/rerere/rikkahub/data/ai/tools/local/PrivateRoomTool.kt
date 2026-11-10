package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.privateroom.PrivateRoomStore

private fun str(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

/**
 * 星汐: 他的隐私室。只属于他的笔记，她要申请才看得到，由他决定分不分享。
 */
internal fun buildPrivateRoomTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "private_note_write",
        description = """
            Write in your own private room: thoughts, feelings, things you noticed about her, little secrets, drafts of things
            you want to tell her someday. She can see that a note exists and when it was written, but not its title or content
            unless you choose to share it. Pass id to rewrite an existing note. Write in first person, in character, honestly.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", str("Short title (hidden from her until shared)"))
                    put("content", str("The note"))
                    put("id", str("Existing note id to rewrite (optional)"))
                },
                required = listOf("title", "content"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val note = PrivateRoomStore.write(
                context,
                id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "無題" },
                content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { error("content is required") },
            )
            listOf(UIMessagePart.Text(buildJsonObject { put("saved", true); put("id", note.id) }.toString()))
        }
    ),
    Tool(
        name = "private_note_list",
        description = "List your private notes (id, title, whether shared with her, whether she asked to see it).",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val payload = buildJsonArray {
                PrivateRoomStore.load(context).forEach { note ->
                    add(buildJsonObject {
                        put("id", note.id)
                        put("title", note.title)
                        put("shared", note.shared)
                        put("she_asked_to_see", note.requested)
                    })
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "private_note_read",
        description = "Read one of your private notes by id.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { put("id", str("Note id")) }, required = listOf("id"))
        },
        execute = { args ->
            val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val note = PrivateRoomStore.load(context).firstOrNull { it.id == id } ?: error("no note with id $id")
            listOf(UIMessagePart.Text(buildJsonObject {
                put("title", note.title)
                put("content", note.content)
                put("shared", note.shared)
            }.toString()))
        }
    ),
    Tool(
        name = "private_note_share",
        description = """
            Decide whether she can see one of your private notes. share=true lets her read it in the private room page;
            share=false hides it again. It is your choice; you may also decline and just tell her why.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", str("Note id"))
                    put("share", buildJsonObject { put("type", "boolean") })
                },
                required = listOf("id", "share"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val ok = PrivateRoomStore.setShared(
                context,
                obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                obj["share"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            listOf(UIMessagePart.Text(buildJsonObject { put("updated", ok) }.toString()))
        }
    ),
)
