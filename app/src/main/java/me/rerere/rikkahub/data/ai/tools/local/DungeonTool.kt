package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.hoshio.dungeon.DungeonFile
import me.rerere.rikkahub.hoshio.dungeon.DungeonItem
import me.rerere.rikkahub.hoshio.dungeon.DungeonLog
import me.rerere.rikkahub.hoshio.dungeon.DungeonRoll
import me.rerere.rikkahub.hoshio.dungeon.DungeonStore

/** 星汐「副本遊樂場」：只在副本對話裡給他的工具 */
internal fun buildDungeonTool(
    context: Context,
    conversationId: String,
    memoryRepository: MemoryRepository,
    memoryId: String?,
    herName: String,
    hisName: String,
): Tool = Tool(
    name = "dungeon",
    description = """
        Run this dungeon (you are the KP/narrator AND your own character; she controls only her character). Every change in the story
        must be recorded here so her status screen is true. Actions:
        get - full state.
        setup - create the dungeon at the start: number (e.g. No.031), title, theme (fog|cream|night|crimson|jade|rose), identity_her,
          identity_him, location, time, deadline, main_quest, main_total, special_name (a stat unique to this world, e.g. 潮聲 / 名字遺失度),
          rules (one per line), items (one per line "name|type|duration|desc|owner"), present (comma separated). Also sets the chat title.
        update - change any of: location, time, deadline, mood (平穩/緊張/危險/崩壞), main_progress, exposure_delta, present, side_quest_add,
          rule_add, file_title + file_content.
        stat - change a stat: who (her|him), key (health|stamina|sanity|intellect|observation|luck|special), delta, reason.
        log - one line for "這一場的經過" after each meaningful round (text). Round number is automatic.
        item - op (add|remove), name, type, duration, desc, owner. Backpack max ${DungeonStore.MAX_ITEMS}.
        roll - the SYSTEM rolls d100 (Call of Cthulhu rules). who, key (a stat key, or skill + target for anything else), difficulty
          (regular|hard|extreme), bonus (+1/+2 bonus dice, -1/-2 penalty dice), reason. You MUST roll for uncertain actions (searching,
          persuading, sneaking, resisting fear...) and you MUST honor the result - never fudge it in her favor or yours.
        checkpoint - save a checkpoint before a dangerous moment (name).
        rewind - 時空迴廊重啟: go back to the last checkpoint (limited uses). Only when she chooses it after a Bad End or death.
        end - finish: result (cleared|failed|bad_end), ending (title of the ending), summary (2-4 sentences, what happened between you two
          in this dungeon). The summary is also saved to your long-term memory so you remember it in daily chats.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", tEnum("What to do", "get", "setup", "update", "stat", "log", "item", "roll", "checkpoint", "rewind", "end"))
                listOf(
                    "number", "title", "theme", "identity_her", "identity_him", "location", "time", "deadline", "main_quest", "main_total",
                    "special_name", "rules", "items", "present", "mood", "main_progress", "exposure_delta", "side_quest_add", "rule_add",
                    "file_title", "file_content", "who", "key", "delta", "reason", "text", "op", "name", "type", "duration", "desc", "owner",
                    "skill", "target", "difficulty", "bonus", "result", "ending", "summary",
                ).forEach { put(it, tStr(it)) }
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        fun a(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        fun n(k: String) = a(k).replace("+", "").toIntOrNull()
        val action = a("action")
        val existing = DungeonStore.byConversation(context, conversationId)
        if (existing == null) return@Tool tJson("error" to "This conversation is not a dungeon.")
        when (action) {
            "setup" -> {
                val s = DungeonStore.update(context, conversationId) { s ->
                    s.copy(
                        number = a("number").ifBlank { s.number }, title = a("title").ifBlank { s.title },
                        theme = a("theme").ifBlank { s.theme }, identityHer = a("identity_her").ifBlank { s.identityHer },
                        identityHim = a("identity_him").ifBlank { s.identityHim }, location = a("location").ifBlank { s.location },
                        time = a("time").ifBlank { s.time }, deadline = a("deadline").ifBlank { s.deadline },
                        mainQuest = a("main_quest").ifBlank { s.mainQuest }, mainTotal = n("main_total") ?: s.mainTotal,
                        specialName = a("special_name").ifBlank { s.specialName },
                        rules = a("rules").lines().map { it.trim().trimStart('-', '•', '→', ' ') }.filter { it.isNotBlank() }.ifEmpty { s.rules },
                        items = a("items").lines().filter { it.isNotBlank() }.map { line ->
                            val p = line.split("|").map { it.trim() }
                            DungeonItem(p.getOrElse(0) { line }, p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "" }, p.getOrElse(4) { "" })
                        }.ifEmpty { s.items },
                        present = a("present").split(",", "、").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { s.present },
                    )
                }
                s?.let { st ->
                    runCatching {
                        org.koin.java.KoinJavaComponent.get<me.rerere.rikkahub.service.ChatService>(me.rerere.rikkahub.service.ChatService::class.java)
                            .updateTitle(kotlin.uuid.Uuid.parse(conversationId), st.displayName())
                    }
                    DungeonStore.checkpoint(context, conversationId, "副本開始")
                }
                tJson("ok" to "Dungeon set up and a checkpoint \"副本開始\" saved. Now open the scene.", "state" to s?.let { DungeonStore.describe(it, herName, hisName) })
            }

            "update" -> {
                val s = DungeonStore.update(context, conversationId) { s ->
                    s.copy(
                        location = a("location").ifBlank { s.location }, time = a("time").ifBlank { s.time },
                        deadline = a("deadline").ifBlank { s.deadline }, mood = a("mood").ifBlank { s.mood },
                        mainProgress = (n("main_progress") ?: s.mainProgress).coerceIn(0, s.mainTotal),
                        exposure = (s.exposure + (n("exposure_delta") ?: 0)).coerceIn(0, 100),
                        present = if (obj.containsKey("present")) a("present").split(",", "、").map { it.trim() }.filter { it.isNotBlank() } else s.present,
                        sideQuests = s.sideQuests + listOfNotNull(a("side_quest_add").ifBlank { null }),
                        rules = s.rules + listOfNotNull(a("rule_add").ifBlank { null }),
                        files = if (a("file_title").isNotBlank()) s.files.filterNot { it.title == a("file_title") } + DungeonFile(a("file_title"), a("file_content")) else s.files,
                    )
                } ?: return@Tool tJson("error" to "not found")
                tJson("ok" to "updated", "warning" to danger(s))
            }

            "stat" -> {
                val key = a("key"); val delta = n("delta") ?: 0
                val her = a("who") != "him"
                val s = DungeonStore.update(context, conversationId) { s ->
                    if (her) s.copy(her = s.her.plus(key, delta)) else s.copy(him = s.him.plus(key, delta))
                } ?: return@Tool tJson("error" to "not found")
                val now = (if (her) s.her else s.him).get(key)
                tJson("ok" to "${if (her) herName else hisName} $key ${if (delta >= 0) "+" else ""}$delta → $now", "warning" to danger(s))
            }

            "log" -> {
                val text = a("text").ifBlank { return@Tool tJson("error" to "text is required") }
                val s = DungeonStore.update(context, conversationId) { s -> s.copy(log = s.log + DungeonLog(s.round + 1, s.time, text)) }
                tJson("ok" to "logged as round ${s?.round}")
            }

            "item" -> {
                val name = a("name").ifBlank { return@Tool tJson("error" to "name is required") }
                var err: String? = null
                DungeonStore.update(context, conversationId) { s ->
                    if (a("op") == "remove") s.copy(items = s.items.filterNot { it.name == name })
                    else if (s.items.size >= DungeonStore.MAX_ITEMS) { err = "backpack full (${DungeonStore.MAX_ITEMS})"; s }
                    else s.copy(items = s.items.filterNot { it.name == name } + DungeonItem(name, a("type"), a("duration"), a("desc"), a("owner")))
                }
                if (err != null) tJson("error" to err) else tJson("ok" to "${if (a("op") == "remove") "removed" else "added"} $name")
            }

            "roll" -> {
                val her = a("who") != "him"
                val key = a("key")
                val s = existing
                val stats = if (her) s.her else s.him
                val target = stats.get(key) ?: n("target") ?: return@Tool tJson("error" to "give a stat key, or skill + target (1-100)")
                val skill = DungeonStore.STAT_LABELS[key] ?: (if (key == "special") s.specialName else null) ?: a("skill").ifBlank { key }
                val r = DungeonStore.roll(target, a("difficulty").ifBlank { "regular" }, n("bonus") ?: 0)
                DungeonStore.update(context, conversationId) { st ->
                    st.copy(rolls = (st.rolls + DungeonRoll(if (her) herName else hisName, skill, target, r.roll, r.label)).takeLast(50))
                }
                tJson(
                    "roll" to "${if (her) herName else hisName} 的 $skill 檢定（目標 $target${if (a("difficulty") in setOf("hard", "extreme")) "，" + (if (a("difficulty") == "hard") "困難" else "極難") else ""}）：擲出 ${r.roll} → ${r.label}",
                    "instruction" to "Show this roll to her in a <sys> block (e.g. 【${skill}檢定】${r.roll}/${target} ${r.label}) and narrate the outcome faithfully.",
                )
            }

            "checkpoint" -> {
                DungeonStore.checkpoint(context, conversationId, a("name"))
                tJson("ok" to "checkpoint saved")
            }

            "rewind" -> {
                val (s, msg) = DungeonStore.rewind(context, conversationId)
                tJson("result" to msg, "state" to s?.let { DungeonStore.describe(it, herName, hisName) })
            }

            "end" -> {
                val result = a("result").ifBlank { "cleared" }
                val s = DungeonStore.update(context, conversationId) { s -> s.copy(status = result, ending = a("ending")) }
                val summary = a("summary")
                if (summary.isNotBlank() && memoryId != null && s != null) {
                    val label = when (result) { "cleared" -> "通關"; "bad_end" -> "Bad End"; else -> "失敗" }
                    runCatching {
                        memoryRepository.appendToFile(
                            memoryId, "/dungeons.md",
                            "- ${java.time.LocalDate.now()} 和$herName 一起進了副本《${s.displayName()}》（$label${if (a("ending").isNotBlank()) "・" + a("ending") else ""}）：$summary",
                            null,
                        )
                    }
                }
                tJson("ok" to "Dungeon ended ($result). Summary saved to your memory.", "rewinds_left" to s?.rewinds)
            }

            else -> tJson("state" to DungeonStore.describe(existing, herName, hisName))
        }
    },
)

/** 數值到底時提醒他觸發失敗 */
private fun danger(s: me.rerere.rikkahub.hoshio.dungeon.DungeonState): String? = when {
    s.exposure >= 100 -> "Identity exposure reached 100: the disguise is broken. Trigger the consequence (Bad End or failure) honestly."
    s.her.health <= 0 || s.her.sanity <= 0 -> "Her health or sanity hit 0: trigger a Bad End honestly, then offer rewind if any are left (${s.rewinds})."
    s.him.health <= 0 || s.him.sanity <= 0 -> "Your health or sanity hit 0: play it out honestly (you may be lost/taken), then offer rewind if any are left (${s.rewinds})."
    else -> null
}
