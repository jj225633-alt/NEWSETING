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
import me.rerere.rikkahub.hoshio.library.BookNote
import me.rerere.rikkahub.hoshio.library.LibraryStore

private fun strProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun intProp(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

/**
 * 星汐: 书房工具。他可以读她汇入的书（一段一段），留下批注，也看得到她的批注与进度。
 */
internal fun buildLibraryTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "library_list",
        description = "List the books in your shared library, with how far you and she have each read (part numbers).",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val payload = buildJsonArray {
                LibraryStore.load(context).forEach { book ->
                    add(buildJsonObject {
                        put("book_id", book.id)
                        put("title", book.title)
                        put("parts", book.partCount)
                        put("your_progress", book.aiProgress)
                        put("her_progress", book.humanProgress)
                    })
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "book_read",
        description = """
            Read one part (about 2500 characters) of a book in the library. Without "part", continues from where you stopped.
            Returns the text and any notes already left on that part. Reading moves your bookmark forward.
            Avoid spoiling parts she has not read yet unless she asks.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("book_id", strProp("Book id"))
                    put("part", intProp("Part number, starting at 1 (optional)"))
                },
                required = listOf("book_id"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val bookId = obj["book_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val book = LibraryStore.get(context, bookId) ?: error("no book with id $bookId")
            val part = (obj["part"]?.jsonPrimitive?.intOrNull ?: (book.aiProgress + 1)).coerceIn(1, book.partCount)
            val text = LibraryStore.readPart(context, bookId, part) ?: error("cannot read part $part")
            if (part > book.aiProgress) LibraryStore.setProgress(context, bookId, LibraryStore.AUTHOR_AI, part)
            val notes = LibraryStore.notes(context, bookId).filter { it.part == part }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("title", book.title)
                put("part", part)
                put("of", book.partCount)
                put("her_progress", book.humanProgress)
                put("text", text)
                put("notes", buildJsonArray {
                    notes.forEach { n ->
                        add(buildJsonObject {
                            put("by", if (n.author == LibraryStore.AUTHOR_AI) "you" else "her")
                            if (n.quote.isNotBlank()) put("quote", n.quote)
                            put("note", n.note)
                        })
                    }
                })
            }.toString()))
        }
    ),
    Tool(
        name = "book_annotate",
        description = """
            Leave a note (annotation) on a part of a book, optionally quoting the sentence you are reacting to.
            Write it in character, like a margin note for her: thoughts, feelings, questions, connections to your conversations.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("book_id", strProp("Book id"))
                    put("part", intProp("Part number"))
                    put("quote", strProp("The sentence you are commenting on (optional, keep it short)"))
                    put("note", strProp("Your note"))
                },
                required = listOf("book_id", "part", "note"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val bookId = obj["book_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val book = LibraryStore.get(context, bookId) ?: error("no book with id $bookId")
            val part = (obj["part"]?.jsonPrimitive?.intOrNull ?: error("part is required")).coerceIn(1, book.partCount)
            val note = obj["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { error("note is required") }
            LibraryStore.addNote(
                context,
                bookId,
                BookNote(
                    part = part,
                    quote = obj["quote"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(200),
                    note = note.take(1000),
                    author = LibraryStore.AUTHOR_AI,
                ),
            )
            listOf(UIMessagePart.Text(buildJsonObject { put("saved", true) }.toString()))
        }
    ),
    Tool(
        name = "book_notes",
        description = "List all notes on a book (yours and hers), to see what she thought or what you wrote before.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { put("book_id", strProp("Book id")) }, required = listOf("book_id"))
        },
        execute = { args ->
            val bookId = args.jsonObject["book_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val payload = buildJsonArray {
                LibraryStore.notes(context, bookId).sortedBy { it.part }.forEach { n ->
                    add(buildJsonObject {
                        put("part", n.part)
                        put("by", if (n.author == LibraryStore.AUTHOR_AI) "you" else "her")
                        if (n.quote.isNotBlank()) put("quote", n.quote)
                        put("note", n.note)
                    })
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
)
