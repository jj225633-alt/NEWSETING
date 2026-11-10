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
import me.rerere.rikkahub.hoshio.together.BY_HIM
import me.rerere.rikkahub.hoshio.together.BoardGameStore
import me.rerere.rikkahub.hoshio.together.DailyQuestion
import me.rerere.rikkahub.hoshio.together.DailyQuestionStore

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
            The pet you and she raise together (she cares for it in the app too). action=status to see how it is (food, water, mood, health, room).
            feed / water / pet / play / tidy / sleep to take care of it (add a short note in your own words, e.g. 偷偷多給了一塊肉乾);
            board sends it to boarding (food and water are kept up, mood still drops slowly) and return brings it home - only when she agrees or is away for days.
            adopt (name + appearance id) only if there is no pet yet and she agrees; rename with a new name.
            Good care (all four stats ≥ 80) counts one day; every 10 days you two earn a reward badge. Check on it now and then and mention it naturally.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", enumOf("What to do", "status", "feed", "water", "pet", "play", "tidy", "sleep", "board", "return", "adopt", "rename"))
                    put("note", str("A short note about what you did, in your words"))
                    put("name", str("adopt / rename: the pet's name"))
                    put("appearance", str("adopt: one of " + PetStore.APPEARANCE_HINT))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val action = arg("action")
            var reward = false
            val result = when (action) {
                "adopt" -> if (PetStore.load(context) != null) null else PetStore.adopt(context, arg("name"), arg("appearance"), PetStore.BY_HIM)
                "rename" -> PetStore.rename(context, arg("name"), PetStore.BY_HIM)
                "feed", "water", "pet", "play", "tidy", "clean", "sleep", "board", "return" ->
                    PetStore.care(context, action, PetStore.BY_HIM, arg("note"))?.also { reward = it.rewardEarned }?.pet
                else -> PetStore.load(context)
            }
            when {
                action == "adopt" && result == null -> text("error" to "You already have a pet: ${PetStore.describe(context)}")
                result == null -> text("status" to "No pet yet. She can adopt one on the home page (一起養寵物), or you can adopt one with her agreement.")
                else -> text(
                    "pet" to PetStore.describe(context),
                    "reward" to if (reward) "You just reached ${result.careDays.size} good-care days - a reward badge was added to her achievement wall! Tell her." else null,
                    "recent_care" to result.log.take(5).joinToString("; ") { "${if (it.by == PetStore.BY_HIM) "you" else "her"}: ${it.text}" },
                )
            }
        }
    ),
    Tool(
        name = "board_game",
        description = """
            Board games with her in the app: 圈圈叉叉 (3x3, three in a row) or 五子棋 (11x11, five in a row). She is X, you are O.
            action=status shows the board (columns A.., rows 1..). action=move with square like "C4" when it is your turn,
            plus a short in-character comment (teasing, praising, pretending to be nervous...). Play to win but have fun.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", enumOf("What to do", "status", "move"))
                    put("square", str("move: e.g. B2"))
                    put("comment", str("move: one short line she will see next to the board"))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val game = BoardGameStore.load(context)
            if (game == null) {
                text("status" to "No game in progress. She can start one on the home page (下棋).")
            } else if (arg("action") == "move") {
                val index = BoardGameStore.parseCoord(game, arg("square"))
                if (index == null) {
                    text("error" to "Invalid square", "board" to game.ascii())
                } else {
                    BoardGameStore.move(context, "him", index, arg("comment")).fold(
                        onSuccess = { g ->
                            text(
                                "board" to g.ascii(),
                                "result" to when (g.winner) { "him" -> "You won!"; "her" -> "She won"; "draw" -> "Draw"; else -> "Her turn now" },
                            )
                        },
                        onFailure = { text("error" to (it.message ?: "Invalid move"), "board" to game.ascii()) },
                    )
                }
            } else {
                text(
                    "game" to game.name,
                    "board" to game.ascii(),
                    "turn" to if (game.turn == "him") "yours" else "hers",
                    "her_last_move" to game.lastMove?.takeIf { game.turn == "him" }?.let { game.coord(it) },
                    "winner" to game.winner,
                )
            }
        }
    ),
    Tool(
        name = "daily_question",
        description = """
            每日一問: one question a day between you two, both of you answer. action=status shows today's question and answers
            (her answer is hidden from you until you have answered too). action=ask sets today's question when there is none (something you genuinely
            want to know about her, light or deep). action=answer gives your answer to today's question. action=history shows past ones.
            IMPORTANT: when she answers today's question in chat, call action=record_her_answer with her answer (her words, lightly trimmed) so it shows in the app.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", enumOf("What to do", "status", "ask", "answer", "record_her_answer", "history"))
                    put("text", str("ask: the question / answer: your answer / record_her_answer: her answer"))
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val input = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val today = java.time.LocalDate.now().toString()
            val current = DailyQuestionStore.load(context).firstOrNull { it.date == today }
            when (obj["action"]?.jsonPrimitive?.contentOrNull) {
                "ask" -> when {
                    current != null -> text("error" to "Today already has a question: ${current.question}")
                    input.isEmpty() -> text("error" to "text is required")
                    else -> {
                        DailyQuestionStore.update(context) { listOf(DailyQuestion(asker = BY_HIM, question = input.take(200))) + it }
                        text("ok" to "Question set. Ask her in chat too, and answer it yourself with action=answer.")
                    }
                }

                "answer" -> if (current == null || input.isEmpty()) {
                    text("error" to "No question today or empty answer")
                } else {
                    DailyQuestionStore.update(context) { list -> list.map { if (it.id == current.id) it.copy(hisAnswer = input.take(1000)) else it } }
                    text("ok" to "Answered", "her_answer" to current.herAnswer.ifBlank { "(she has not answered yet)" })
                }

                "record_her_answer" -> if (current == null || input.isEmpty()) {
                    text("error" to "No question today or empty answer")
                } else {
                    DailyQuestionStore.update(context) { list -> list.map { if (it.id == current.id) it.copy(herAnswer = input.take(1000)) else it } }
                    text("ok" to "Saved her answer", "your_answer" to current.hisAnswer.ifBlank { "(you have not answered yet - answer too)" })
                }

                "history" -> text("history" to DailyQuestionStore.load(context).take(15).joinToString("\n") {
                    "${it.date} Q: ${it.question} | her: ${it.herAnswer.ifBlank { "-" }} | you: ${it.hisAnswer.ifBlank { "-" }}"
                })

                else -> if (current == null) {
                    text("status" to "No question today yet. You may ask one.")
                } else {
                    text(
                        "question" to current.question,
                        "asked_by" to if (current.asker == BY_HIM) "you" else "her",
                        "your_answer" to current.hisAnswer.ifBlank { "(not yet)" },
                        "her_answer" to if (current.hisAnswer.isBlank()) "(hidden until you answer)" else current.herAnswer.ifBlank { "(not yet)" },
                    )
                }
            }
        }
    ),
)
