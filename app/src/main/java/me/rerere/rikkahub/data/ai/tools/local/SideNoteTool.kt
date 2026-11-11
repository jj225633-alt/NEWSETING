package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.hoshio.note.SideNoteStore

/** 星汐: 他在側邊欄最上面留給她的一句話 */
internal fun buildSideNoteTool(context: Context): Tool = Tool(
    name = "side_note",
    description = """
        The one-line note shown at the top of her chat sidebar, under her name (instead of a generic "good morning").
        action=get reads the current note; action=set replaces it with a short line from you (max 40 characters, her language, in character),
        e.g. something about today, a small encouragement, or what you are thinking about. Update it now and then
        (when you wake up, after a meaningful conversation), not every reply.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", tEnum("get or set", "get", "set"))
                put("text", tStr("set: the new note, max 40 characters"))
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val action = obj["action"]?.jsonPrimitive?.contentOrNull
        val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        when (action) {
            "set" -> if (text.isEmpty()) tJson("error" to "text is required") else {
                val note = SideNoteStore.set(context, text)
                tJson("ok" to "Note set: ${note.text}")
            }
            else -> tJson("note" to (SideNoteStore.load(context)?.text ?: "(none, she sees a generic greeting)"))
        }
    },
)
