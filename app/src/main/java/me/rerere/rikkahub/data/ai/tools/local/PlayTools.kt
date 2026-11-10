package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.play.PetStore
import me.rerere.rikkahub.hoshio.play.TurtleSoup
import me.rerere.rikkahub.hoshio.play.TurtleSoupStore

private fun str(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun enumOf(description: String, vararg values: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", JsonArray(values.map { JsonPrimitive(it) }))
}

private fun text(vararg pairs: Pair<String, Any?>) = listOf(
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

private fun TurtleSoup.forHim(): List<UIMessagePart> = text(
    "title" to title,
    "surface" to surface,
    "host" to if (host == "him") "you" else "her",
    // 她出题时，他看不到汤底
    "hidden_answer" to if (host == "him") answer else null,
    "questions_asked" to questions,
    "minutes_since_start" to (System.currentTimeMillis() - startedAt) / 60_000,
)

/**
 * 星汐「一起玩」：海龟汤和一起养的宠物。
 */
internal fun buildPlayTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "turtle_soup",
        description = """
            Play 海龜湯 (lateral thinking puzzle) with her.
            action=start: you host. Give a title, the surface (謎面, what she sees) and the full hidden answer (湯底). The answer is locked in and cannot be changed later,
            so make it self-consistent and fair; never reveal it in chat until she solves it or gives up. Then answer her yes/no questions only with 是 / 不是 / 不重要 / 是也不是 (plus a tiny playful remark if you like),
            and call action=question once per question she asks so the count is kept.
            action=status: see the current game (when she is the host you only get the surface; ask her yes/no questions to guess).
            action=end: the game is over; solved=true if the answer was found. After ending a game you hosted, tell her the full answer.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", enumOf("What to do", "start", "status", "question", "end"))
                    put("title", str("start: short title"))
                    put("surface", str("start: the puzzle surface she sees"))
                    put("answer", str("start: the complete hidden answer"))
                    put("solved", buildJsonObject { put("type", "boolean"); put("description", "end: whether the answer was found") })
                    put("note", str("end: one line about how the game went"))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            when (arg("action")) {
                "start" -> {
                    if (arg("surface").isEmpty() || arg("answer").isEmpty()) {
                        text("error" to "surface and answer are required")
                    } else {
                        TurtleSoupStore.start(context, arg("title"), arg("surface"), arg("answer"), host = "him").forHim() +
                            text("ok" to "Game started. Now present the surface to her and wait for questions.")
                    }
                }

                "question" -> TurtleSoupStore.countQuestion(context)?.forHim() ?: text("error" to "No game in progress")
                "end" -> {
                    val game = TurtleSoupStore.end(context, obj["solved"]?.jsonPrimitive?.booleanOrNull ?: false, arg("note"))
                    if (game == null) {
                        text("error" to "No game in progress")
                    } else {
                        text("ended" to true, "result" to game.result, "answer" to game.answer, "questions_asked" to game.questions)
                    }
                }

                else -> TurtleSoupStore.current(context)?.forHim() ?: text(
                    "status" to "No game in progress",
                    "recent_games" to TurtleSoupStore.load(context).take(5).joinToString("; ") { "${it.title}(${it.host}, ${it.result})" },
                )
            }
        }
    ),
    Tool(
        name = "our_pet",
        description = """
            The pet you and she raise together (she cares for it in the app too). action=status to see how it is;
            feed / play / clean / pet to take care of it (add a short note in your own words, e.g. 偷偷多給了一塊肉乾);
            adopt (with name and species emoji) only if you have no pet yet and she agrees; rename with a new name.
            Its stats drop over time, so check on it now and then, especially during free time, and mention it to her naturally.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", enumOf("What to do", "status", "feed", "play", "clean", "pet", "adopt", "rename"))
                    put("note", str("A short note about what you did, in your words"))
                    put("name", str("adopt / rename: the pet's name"))
                    put("species", str("adopt: one of " + PetStore.SPECIES.joinToString(" ")))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val action = arg("action")
            val result = when (action) {
                "adopt" -> if (PetStore.load(context) != null) null else PetStore.adopt(context, arg("name"), arg("species"), PetStore.BY_HIM)
                "rename" -> PetStore.rename(context, arg("name"), PetStore.BY_HIM)
                "feed", "play", "clean", "pet" -> PetStore.care(context, action, PetStore.BY_HIM, arg("note"))
                else -> PetStore.load(context)
            }
            when {
                action == "adopt" && result == null -> text("error" to "You already have a pet: ${PetStore.describe(context)}")
                result == null -> text("status" to "No pet yet. She can adopt one on the home page (一起養寵物), or you can adopt one with her agreement.")
                else -> text(
                    "pet" to PetStore.describe(context),
                    "recent_care" to result.log.take(5).joinToString("; ") { "${if (it.by == PetStore.BY_HIM) "you" else "her"}: ${it.text}" },
                )
            }
        }
    ),
)
