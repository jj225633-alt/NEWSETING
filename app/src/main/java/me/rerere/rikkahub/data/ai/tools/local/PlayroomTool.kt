package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.playroom.PlayroomStore

const val PLAYROOM_CREATE_TOOL = "playroom_create"
const val PLAYROOM_UPDATE_TOOL = "playroom_update"

private fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

/**
 * 星汐: 游戏室工具。他可以写小游戏、小网页、互动卡片，作品会存进游戏室，同时直接显示在对话里。
 */
internal fun buildPlayroomTools(context: Context): List<Tool> = listOf(
    Tool(
        name = PLAYROOM_CREATE_TOOL,
        description = """
            Make a small interactive thing for her: a mini game, a little web page, a card, a quiz, an animation, a tool, etc.
            Provide ONE complete self-contained HTML document (inline CSS and JavaScript; no external files except well-known CDNs).
            It must work on a phone screen (responsive, touch friendly). It is saved in the "game room" and shown inline in this chat.
            Prefer this over pasting HTML into your message when you want her to actually use what you made.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", stringProp("Short title shown in the game room"))
                    put("description", stringProp("One sentence about what it is"))
                    put("html", stringProp("Complete self-contained HTML document"))
                },
                required = listOf("title", "html"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "未命名作品" }
            val description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val html = obj["html"]?.jsonPrimitive?.contentOrNull.orEmpty()
            require(html.isNotBlank()) { "html is required" }
            val item = PlayroomStore.create(context, title, description, html)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("id", item.id)
                put("title", item.title)
            }.toString()))
        }
    ),
    Tool(
        name = PLAYROOM_UPDATE_TOOL,
        description = """
            Update one of your game room items by id: replace its HTML (complete document) and/or its title or description.
            Use playroom_read first if you need the current HTML. The updated version is shown inline in this chat.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", stringProp("Item id"))
                    put("title", stringProp("New title (optional)"))
                    put("description", stringProp("New description (optional)"))
                    put("html", stringProp("New complete HTML document (optional)"))
                },
                required = listOf("id"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val item = PlayroomStore.update(
                context = context,
                id = id,
                html = obj["html"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim(),
            )
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("id", item.id)
                put("title", item.title)
            }.toString()))
        }
    ),
    Tool(
        name = "playroom_list",
        description = "List the items in your game room (id, title, description).",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val payload = buildJsonArray {
                PlayroomStore.load(context).forEach { item ->
                    add(buildJsonObject {
                        put("id", item.id)
                        put("title", item.title)
                        put("description", item.description)
                    })
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "playroom_read",
        description = "Read the full HTML of one game room item by id, e.g. before improving it.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { put("id", stringProp("Item id")) },
                required = listOf("id"),
            )
        },
        execute = { args ->
            val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val html = PlayroomStore.readHtml(context, id) ?: error("no item with id $id")
            listOf(UIMessagePart.Text(html))
        }
    ),
)
