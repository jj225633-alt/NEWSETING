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

/** 星汐: 看自己截過的聊天截圖 */
internal fun buildPovSnapTool(context: Context): Tool = Tool(
    name = "my_snapshots",
    description = "See the chat screenshots you took from your point of view (<pov-snap .../>), newest first, with the lines and your note.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val list = me.rerere.rikkahub.hoshio.snap.PovSnap.load(context).filter { it.lines != null }.take(10)
        tJson("snapshots" to if (list.isEmpty()) "none yet" else list.joinToString("\n\n") { s ->
            java.time.Instant.ofEpochMilli(s.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() +
                (if (s.note.isNotBlank()) " - ${s.note}" else "") + "\n" +
                s.lines.orEmpty().joinToString("\n") { (if (it.him) "you: " else "her: ") + it.text.take(120) }
        })
    },
)
