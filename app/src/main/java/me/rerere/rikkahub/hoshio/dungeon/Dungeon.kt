package me.rerere.rikkahub.hoshio.dungeon

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/*
 * 星汐「副本遊樂場」：無限流／跑團式的副本。每個副本是一場獨立的對話，他同時當 KP（旁白主持）和自己的角色。
 * 狀態（數值、背包、經過、規則…）存在這裡，由他用 dungeon 工具更新；擲骰由系統擲（COC 式 d100），他沒辦法放水。
 */

@Serializable
data class DungeonStats(
    val health: Int = 100,
    val stamina: Int = 100,
    val sanity: Int = 100,
    val intellect: Int = 70,
    val observation: Int = 65,
    val luck: Int = 50,
    val special: Int = 0,
) {
    fun get(key: String): Int? = when (key) {
        "health" -> health; "stamina" -> stamina; "sanity" -> sanity
        "intellect" -> intellect; "observation" -> observation; "luck" -> luck; "special" -> special
        else -> null
    }

    fun plus(key: String, delta: Int): DungeonStats {
        fun c(v: Int) = v.coerceIn(0, if (key == "special") 999 else 100)
        return when (key) {
            "health" -> copy(health = c(health + delta)); "stamina" -> copy(stamina = c(stamina + delta))
            "sanity" -> copy(sanity = c(sanity + delta)); "intellect" -> copy(intellect = c(intellect + delta))
            "observation" -> copy(observation = c(observation + delta)); "luck" -> copy(luck = c(luck + delta))
            "special" -> copy(special = c(special + delta))
            else -> this
        }
    }
}

@Serializable
data class DungeonItem(val name: String, val type: String = "", val duration: String = "", val desc: String = "", val owner: String = "")

@Serializable
data class DungeonLog(val round: Int, val time: String, val text: String, val at: Long = System.currentTimeMillis())

@Serializable
data class DungeonFile(val title: String, val content: String)

@Serializable
data class DungeonRoll(val who: String, val skill: String, val target: Int, val roll: Int, val result: String, val at: Long = System.currentTimeMillis())

@Serializable
data class DungeonCheckpoint(val name: String, val at: Long, val state: String)

@Serializable
data class DungeonState(
    val id: String,
    val conversationId: String,
    /** 她開副本時的要求（隨機或指定世界觀） */
    val request: String = "",
    val number: String = "",
    val title: String = "",
    val theme: String = "fog",
    val identityHer: String = "",
    val identityHim: String = "",
    val location: String = "",
    val time: String = "",
    val deadline: String = "",
    val mood: String = "平穩",
    val mainQuest: String = "",
    val mainProgress: Int = 0,
    val mainTotal: Int = 4,
    val sideQuests: List<String> = emptyList(),
    val exposure: Int = 0,
    val specialName: String = "",
    val her: DungeonStats = DungeonStats(),
    val him: DungeonStats = DungeonStats(),
    val present: List<String> = emptyList(),
    val log: List<DungeonLog> = emptyList(),
    val items: List<DungeonItem> = emptyList(),
    val rules: List<String> = emptyList(),
    val files: List<DungeonFile> = emptyList(),
    val rolls: List<DungeonRoll> = emptyList(),
    val checkpoints: List<DungeonCheckpoint> = emptyList(),
    val rewinds: Int = 3,
    /** active | cleared | failed | bad_end */
    val status: String = "active",
    val ending: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val ready: Boolean get() = title.isNotBlank()
    val round: Int get() = (log.maxOfOrNull { it.round } ?: 0)
    fun displayName() = listOf(number, title).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "準備中的副本" }
}

object DungeonStore {
    private const val PREFS = "hoshio_dungeons"
    const val MAX_ITEMS = 20
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _all = MutableStateFlow<List<DungeonState>?>(null)
    val all: StateFlow<List<DungeonState>?> = _all.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<DungeonState> {
        _all.value?.let { return it }
        val list = prefs(context).getString("list", null)?.let { runCatching { json.decodeFromString<List<DungeonState>>(it) }.getOrNull() }.orEmpty()
        _all.value = list
        return list
    }

    fun byConversation(context: Context, conversationId: String): DungeonState? = load(context).firstOrNull { it.conversationId == conversationId }

    @Synchronized
    fun save(context: Context, list: List<DungeonState>) {
        prefs(context).edit { putString("list", json.encodeToString(list)) }
        _all.value = list
    }

    fun create(context: Context, conversationId: String, request: String): DungeonState {
        val d = DungeonState(id = kotlin.uuid.Uuid.random().toString().take(8), conversationId = conversationId, request = request)
        save(context, listOf(d) + load(context))
        return d
    }

    @Synchronized
    fun update(context: Context, conversationId: String, fn: (DungeonState) -> DungeonState): DungeonState? {
        val list = load(context)
        val cur = list.firstOrNull { it.conversationId == conversationId } ?: return null
        val next = fn(cur).copy(updatedAt = System.currentTimeMillis())
        save(context, list.map { if (it.conversationId == conversationId) next else it })
        return next
    }

    fun remove(context: Context, id: String) = save(context, load(context).filterNot { it.id == id })

    /** 存檔點：存下目前狀態（不含存檔點本身），最多留 5 個 */
    fun checkpoint(context: Context, conversationId: String, name: String) = update(context, conversationId) { s ->
        val snap = json.encodeToString(s.copy(checkpoints = emptyList()))
        s.copy(checkpoints = (s.checkpoints + DungeonCheckpoint(name.ifBlank { "第 ${s.round} 輪" }, System.currentTimeMillis(), snap)).takeLast(5))
    }

    /** 時空迴廊重啟：回到最近的存檔點，消耗一次重溯 */
    fun rewind(context: Context, conversationId: String): Pair<DungeonState?, String> {
        var msg = ""
        val r = update(context, conversationId) { s ->
            val cp = s.checkpoints.lastOrNull()
            when {
                cp == null -> { msg = "no checkpoint saved"; s }
                s.rewinds <= 0 -> { msg = "no rewinds left"; s }
                else -> {
                    val restored = runCatching { json.decodeFromString<DungeonState>(cp.state) }.getOrNull()
                    if (restored == null) { msg = "checkpoint broken"; s } else {
                        msg = "rewound to checkpoint \"${cp.name}\""
                        restored.copy(checkpoints = s.checkpoints, rewinds = s.rewinds - 1, status = "active", ending = "",
                            log = restored.log + DungeonLog(restored.round + 1, restored.time, "【時空迴廊重啟】回到「${cp.name}」"))
                    }
                }
            }
        }
        return r to msg
    }

    // ---- 擲骰（COC 7 版的 d100）----

    data class RollResult(val roll: Int, val target: Int, val level: String) {
        val label: String get() = when (level) {
            "critical" -> "大成功"; "extreme" -> "極難成功"; "hard" -> "困難成功"; "regular" -> "成功"
            "fumble" -> "大失敗"; else -> "失敗"
        }
        val success: Boolean get() = level in setOf("critical", "extreme", "hard", "regular")
    }

    /** [difficulty]：regular / hard / extreme；[bonus] > 0 獎勵骰、< 0 懲罰骰 */
    fun roll(target: Int, difficulty: String = "regular", bonus: Int = 0, random: Random = Random.Default): RollResult {
        val units = random.nextInt(10)
        val tensPool = List(1 + kotlin.math.abs(bonus)) { random.nextInt(10) }
        fun value(tens: Int) = (tens * 10 + units).let { if (it == 0) 100 else it }
        val roll = if (bonus >= 0) tensPool.minOf { value(it) } else tensPool.maxOf { value(it) }
        val t = target.coerceIn(1, 100)
        val level = when {
            roll == 1 -> "critical"
            roll == 100 || (t < 50 && roll >= 96) -> "fumble"
            roll <= t / 5 -> "extreme"
            roll <= t / 2 -> "hard"
            roll <= t -> "regular"
            else -> "fail"
        }
        val need = when (difficulty) { "extreme" -> setOf("critical", "extreme"); "hard" -> setOf("critical", "extreme", "hard"); else -> null }
        val final = if (need != null && level !in need && level != "fumble") "fail" else level
        return RollResult(roll, t, final)
    }

    val STAT_LABELS = linkedMapOf(
        "health" to "健康", "stamina" to "體力", "sanity" to "精神",
        "intellect" to "智力", "observation" to "觀察力", "luck" to "運勢",
    )

    /** 給他看的狀態摘要 */
    fun describe(s: DungeonState, herName: String, hisName: String): String = buildString {
        appendLine("Dungeon: ${s.displayName()} (status: ${s.status}, round ${s.round})")
        if (s.identityHer.isNotBlank()) appendLine("$herName's identity: ${s.identityHer}")
        if (s.identityHim.isNotBlank()) appendLine("Your identity ($hisName): ${s.identityHim}")
        appendLine("Location: ${s.location} | In-world time: ${s.time} | Deadline: ${s.deadline} | Mood: ${s.mood}")
        appendLine("Main quest (${s.mainProgress}/${s.mainTotal}): ${s.mainQuest}")
        if (s.sideQuests.isNotEmpty()) appendLine("Side quests: ${s.sideQuests.joinToString("; ")}")
        appendLine("Identity exposure: ${s.exposure}/100")
        fun st(x: DungeonStats) = STAT_LABELS.entries.joinToString(", ") { "${it.key} ${x.get(it.key)}" } +
            if (s.specialName.isNotBlank()) ", special(${s.specialName}) ${x.special}" else ""
        appendLine("$herName: ${st(s.her)}")
        appendLine("$hisName (you): ${st(s.him)}")
        if (s.present.isNotEmpty()) appendLine("Present: ${s.present.joinToString("、")}")
        if (s.items.isNotEmpty()) appendLine("Backpack (${s.items.size}/$MAX_ITEMS): " + s.items.joinToString("; ") { "${it.name}[${it.type}${if (it.duration.isNotBlank()) "/" + it.duration else ""}]" + (if (it.owner.isNotBlank()) "(${it.owner})" else "") })
        if (s.rules.isNotEmpty()) appendLine("World rules:\n" + s.rules.joinToString("\n") { "- $it" })
        if (s.files.isNotEmpty()) appendLine("Files: " + s.files.joinToString("; ") { it.title })
        s.log.takeLast(6).forEach { appendLine("Log r${it.round} ${it.time}: ${it.text}") }
        appendLine("Rewinds left: ${s.rewinds}; checkpoints: ${s.checkpoints.joinToString(", ") { it.name }.ifBlank { "none" }}")
    }
}

/** 這則使用者訊息是「悄悄說」（場外說話） */
const val WHISPER_PREFIX = "〔悄悄說〕"
