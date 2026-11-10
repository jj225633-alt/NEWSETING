package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.diary.DiaryAuthor
import me.rerere.rikkahub.hoshio.diary.DiaryEntry
import me.rerere.rikkahub.hoshio.diary.DiaryStore
import java.time.LocalDate

/**
 * 星汐: 日记工具。AI 可以翻日记（他自己写的，以及她允许他看的），也可以在她要求时写一篇。
 */
internal fun buildDiaryTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "diary_list",
        description = """
            List the diary entries you can see: your own daily diary and the entries she chose to share with you.
            Returns dates, who wrote each entry and a short preview, newest first.
            Use it when she asks what happened on some day, or when you want to recall past days accurately instead of guessing.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of entries (default 14, max 60)")
                })
            })
        },
        execute = { args ->
            val limit = (args.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 14).coerceIn(1, 60)
            val payload = buildJsonArray {
                DiaryStore.visibleToAi(context).take(limit).forEach { entry ->
                    add(buildJsonObject {
                        put("date", entry.date)
                        put("author", if (entry.author == DiaryAuthor.AI) "you" else "her")
                        put("preview", entry.content.take(80))
                    })
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "diary_read",
        description = """
            Read the full diary entries of one date (yyyy-MM-dd) that you can see.
            What is written there is what actually happened that day; prefer it over vague recollection.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("date", buildJsonObject {
                        put("type", "string")
                        put("description", "Date in yyyy-MM-dd")
                    })
                },
                required = listOf("date"),
            )
        },
        execute = { args ->
            val date = args.jsonObject["date"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val payload = buildJsonArray {
                DiaryStore.visibleToAi(context).filter { it.date == date }.sortedBy { it.createdAt }.forEach { entry ->
                    add(buildJsonObject {
                        put("author", if (entry.author == DiaryAuthor.AI) "you" else "her")
                        put("content", entry.content)
                    })
                }
            }
            listOf(UIMessagePart.Text(if (payload.isEmpty()) "{\"result\":\"no diary entries you can see on $date\"}" else payload.toString()))
        }
    ),
    Tool(
        name = "diary_write",
        description = """
            Write an entry in your own diary. Only use this when she explicitly asks you to write something in the diary;
            your daily diary is written automatically. Write in first person, in character, and only about things that really happened.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The diary text")
                    })
                    put("date", buildJsonObject {
                        put("type", "string")
                        put("description", "Date in yyyy-MM-dd; defaults to today")
                    })
                },
                required = listOf("content"),
            )
        },
        execute = { args ->
            val content = args.jsonObject["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(content.isNotBlank()) { "content is required" }
            val date = args.jsonObject["date"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
                ?: LocalDate.now().toString()
            DiaryStore.add(context, DiaryEntry(date = date, author = DiaryAuthor.AI, content = content))
            listOf(UIMessagePart.Text("{\"result\":\"saved\",\"date\":\"$date\"}"))
        }
    ),
)
