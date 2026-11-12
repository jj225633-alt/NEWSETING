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
    /** 另開一次模型請求當「封存劇本的 KP」，他看不到這次請求的內容 */
    generate: (suspend (String) -> String?)? = null,
): Tool = Tool(
    name = "dungeon",
    description = """
        Run this dungeon (you are the KP/narrator AND your own character; she controls only her character). Every change in the story
        must be recorded here so her status screen is true. Actions:
        get - full state.
        setup - call with NO other params at the start: a separate hidden KP writes the sealed script (truth, clues, story beats, which
          rule is false) that you cannot see; you get only the public setup to open the scene with. (Fallback if that fails: give number (e.g. No.031), title, theme (fog|cream|night|crimson|jade|rose), identity_her,
          identity_him, location, time, deadline, main_quest, main_total, special_name (a stat unique to this world, e.g. 潮聲 / 名字遺失度),
          rules (one per line), items (one per line "name|type|duration|desc|owner"), present (comma separated).) Also sets the chat title.
        clue - after a SUCCESSFUL investigation roll, ask the sealed script what they found: where (what/where they searched, in Chinese).
          It returns a clue only if the script has one there (or on an extreme/critical success). Narrate exactly that; never invent major clues
          or the truth yourself.
        next_beat - draw the next sealed story beat (what happens next in the world). Call when in-world time jumps to a new segment
          (evening, night, next day), when they rest, when the scene stalls, or after main_progress goes up. Narrate it faithfully.
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
                put("action", tEnum("What to do", "get", "setup", "clue", "next_beat", "update", "stat", "log", "item", "roll", "checkpoint", "rewind", "end"))
                listOf(
                    "number", "title", "theme", "identity_her", "identity_him", "location", "time", "deadline", "main_quest", "main_total",
                    "special_name", "rules", "items", "present", "mood", "main_progress", "exposure_delta", "side_quest_add", "rule_add",
                    "file_title", "file_content", "who", "key", "delta", "reason", "text", "op", "name", "type", "duration", "desc", "owner",
                    "skill", "target", "difficulty", "bonus", "result", "ending", "summary", "where",
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
            "setup" -> run setup@{
                // 星汐: 先試著讓另一個 KP 寫封存劇本
                if (a("title").isBlank() && generate != null && !existing.ready) {
                    val raw = runCatching { generate(kpPrompt(existing.request, herName, hisName)) }.getOrNull()
                    val made = raw?.let { parseScript(it) }
                    if (made != null) {
                        val s = DungeonStore.update(context, conversationId) { s -> made(s) }
                        s?.let { st ->
                            runCatching {
                                org.koin.java.KoinJavaComponent.get<me.rerere.rikkahub.service.ChatService>(me.rerere.rikkahub.service.ChatService::class.java)
                                    .updateTitle(kotlin.uuid.Uuid.parse(conversationId), st.displayName())
                            }
                            DungeonStore.checkpoint(context, conversationId, "副本開始")
                        }
                        return@setup tJson(
                            "ok" to "The sealed script is ready (you cannot see it). Open the dungeon now with a <sys> block (【副本載入】, identities, main quest, world rules, 【副本開始】) and the first scene. Do not invent the truth; discover it with her through clue and next_beat.",
                            "public_state" to s?.let { DungeonStore.describe(it, herName, hisName) },
                        )
                    }
                }
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

            "clue" -> {
                val where = a("where")
                val last = existing.rolls.lastOrNull()
                val fresh = last != null && System.currentTimeMillis() - last.at < 20 * 60_000 && last.at > existing.clueRollAt
                val ok = fresh && last!!.result.contains("成功")
                if (!ok) return@Tool tJson("result" to "No fresh successful roll. Roll an investigation first (observation/intellect/luck); on failure they find nothing important.")
                val big = last!!.result == "大成功" || last.result == "極難成功"
                val pool = existing.clues.withIndex().filter { !it.value.revealed }
                val pick = pool.firstOrNull { matches(where, it.value.where) } ?: if (big) pool.firstOrNull() else null
                DungeonStore.update(context, conversationId) { st ->
                    var next = st.copy(clueRollAt = last.at)
                    if (pick != null) {
                        next = next.copy(
                            clues = st.clues.mapIndexed { i, c -> if (i == pick.index) c.copy(revealed = true, revealedAt = System.currentTimeMillis()) else c },
                            files = st.files + DungeonFile("線索 ${st.clues.count { it.revealed } + 1}", pick.value.text),
                        )
                    }
                    next
                }
                if (pick == null) tJson("result" to "Nothing important here. Describe only small atmospheric details; do not invent a major clue. Remaining sealed clues: ${pool.size}.")
                else tJson("clue" to pick.value.text, "instruction" to "Narrate how they find exactly this. It is now in her status screen under 檔案.")
            }

            "next_beat" -> {
                val idx = existing.beats.indexOfFirst { !it.revealed }
                if (idx < 0) return@Tool tJson("result" to "No sealed beats left: drive toward the climax and ending based on what is known.")
                DungeonStore.update(context, conversationId) { st ->
                    st.copy(beats = st.beats.mapIndexed { i, b -> if (i == idx) b.copy(revealed = true, revealedAt = System.currentTimeMillis()) else b })
                }
                tJson("beat" to existing.beats[idx].text, "left" to existing.beats.size - idx - 1, "instruction" to "This happens now. Narrate it in the scene faithfully; you may add sensory detail but not change it.")
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
                tJson(
                    "ok" to "Dungeon ended ($result). Summary saved to your memory.",
                    "rewinds_left" to s?.rewinds,
                    "truth" to existing.truth.ifBlank { null },
                    "false_rule" to existing.rules.getOrNull(existing.falseRule),
                    "instruction" to "Now you may reveal the truth (and which rule was false) to her in a <sys> block 【真相】.",
                )
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

/** 「她查的地方」和「封存線索藏的地方」有沒有對上：有兩個字以上相同就算 */
private fun matches(where: String, hidden: String): Boolean {
    if (where.isBlank() || hidden.isBlank()) return false
    val keys = hidden.split(Regex("[、，,／/ ]+")).map { it.trim() }.filter { it.length >= 2 }
    if (keys.any { where.contains(it) || it.contains(where) }) return true
    return (0 until hidden.length - 1).any { where.contains(hidden.substring(it, it + 2)) }
}

private fun kpPrompt(request: String, her: String, him: String) = """
你是一個無限流／克蘇魯跑團（COC）副本的封存劇本作者。玩家是 $her 和她的搭檔 $him（兩個人一起進副本）。
$him 之後會當現場的旁白主持，但他看不到你寫的封存內容，只能靠查線索和抽劇情節點知道後面會發生什麼。
她的要求：${request.ifBlank { "隨機副本" }}
請原創一個副本（不要用現成作品），用繁體中文，只輸出一個 JSON，不要其他文字：
{
 "number": "No.0xx", "title": "副本名", "theme": "fog|cream|night|crimson|jade|rose 擇一，符合氛圍",
 "identity_her": "她的身份，能去哪裡／不能去哪裡", "identity_him": "他的身份，能去哪裡／不能去哪裡",
 "location": "開場地點", "time": "第 1 天 hh:mm", "deadline": "時限", "main_quest": "主線目標", "main_total": 3到5,
 "special_name": "這個世界特有的數值名稱", "rules": ["3到5條詭異的世界規則，其中一條是假的"], "false_rule": 假規則的索引(從0起),
 "items": [{"name":"","type":"身份綁定型／功能型／消耗型","duration":"永久／一次","desc":"","owner":"$her 或 $him 或空"}],
 "present": ["開場在場的角色"],
 "clues": [{"where":"藏在哪裡（地點或物品，幾個關鍵詞）","text":"查到時得到的具體線索"}]（6到9條，其中至少一條暗示哪條規則是假的）,
 "beats": ["依時間順序會發生的劇情節點，每條一兩句，逐步升級到高潮"]（5到8條）,
 "truth": "副本的完整真相（結束時才揭曉）"
}
""".trimIndent()

/** 把 KP 寫的 JSON 變成副本狀態；看不懂就回 null，改用他自己設定 */
private fun parseScript(raw: String): ((me.rerere.rikkahub.hoshio.dungeon.DungeonState) -> me.rerere.rikkahub.hoshio.dungeon.DungeonState)? {
    val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val obj = runCatching { kotlinx.serialization.json.Json { isLenient = true }.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
    fun str(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    fun arr(k: String) = (obj[k] as? kotlinx.serialization.json.JsonArray).orEmpty()
    fun strs(k: String) = arr(k).mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotBlank() }
    fun field(e: kotlinx.serialization.json.JsonElement, k: String) = ((e as? JsonObject)?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (str("title").isBlank()) return null
    return { s ->
        s.copy(
            number = str("number"), title = str("title"), theme = str("theme").ifBlank { "fog" },
            identityHer = str("identity_her"), identityHim = str("identity_him"), location = str("location"), time = str("time"),
            deadline = str("deadline"), mainQuest = str("main_quest"), mainTotal = str("main_total").toIntOrNull() ?: 4,
            specialName = str("special_name"), rules = strs("rules"), falseRule = str("false_rule").toIntOrNull() ?: -1,
            items = arr("items").map { e -> DungeonItem(field(e, "name"), field(e, "type"), field(e, "duration"), field(e, "desc"), field(e, "owner")) }.filter { it.name.isNotBlank() },
            present = strs("present"),
            clues = arr("clues").map { e -> me.rerere.rikkahub.hoshio.dungeon.DungeonSealed(text = field(e, "text"), where = field(e, "where")) }.filter { it.text.isNotBlank() },
            beats = strs("beats").map { me.rerere.rikkahub.hoshio.dungeon.DungeonSealed(text = it) },
            truth = str("truth"),
        )
    }
}
