package me.rerere.rikkahub.hoshio.play

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private val petJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class PetLog(val at: Long = System.currentTimeMillis(), val by: String, val text: String)

/**
 * 两个人一起养的宠物（规则参考 Orbis 的宠物房间）。
 * 数值 0–100；读取时依经过的真实时间结算：饱足每小时 -4、水分 -5、心情 -2（寄养时 -0.5），
 * 吃喝心情都够时健康慢慢回升，不够的时间健康会下降。寄养中饱足和水分维持在 80 以上。
 */
@Serializable
data class Pet(
    val name: String,
    /** 旧版的 emoji；新版用 [appearance] 的图片 */
    val species: String = "🐱",
    val appearance: String? = null,
    val bornAt: Long = System.currentTimeMillis(),
    val fullness: Int = 90,
    val water: Int = 90,
    val happiness: Int = 90,
    val health: Int = 100,
    val cleanliness: Int = 100,
    val messy: Boolean = false,
    val boarding: Boolean = false,
    val sleepingUntil: Long = 0,
    /** 四项都 ≥ 80 时照顾过的日子 */
    val careDays: Set<String> = emptySet(),
    /** 每满 10 天得到的奖励次数 */
    val rewards: Int = 0,
    /** sage | rose | sky */
    val decoration: String = "sage",
    val updatedAt: Long = System.currentTimeMillis(),
    val log: List<PetLog> = emptyList(),
) {
    val ageDays: Long get() = (System.currentTimeMillis() - bornAt) / TimeUnit.DAYS.toMillis(1)
    val family: String get() = PetCatalog.find(appearance)?.family ?: "cat"
    val sleeping: Boolean get() = sleepingUntil > System.currentTimeMillis()

    val status: String
        get() = when {
            boarding -> "寄養中"
            sleeping -> "在睡覺"
            health < 40 -> "身體不太舒服"
            fullness < 25 -> "餓壞了"
            water < 25 -> "好渴"
            happiness < 25 -> "很寂寞"
            messy -> "房間亂亂的"
            fullness < 50 -> "有點餓"
            water < 50 -> "有點渴"
            happiness < 50 -> "有點無聊"
            fullness > 80 && happiness > 80 -> "超開心"
            else -> "很好"
        }

    /** 现在该用哪一格动作图 */
    val frame: String
        get() = when {
            sleeping -> "sleep"
            happiness >= 80 && fullness >= 60 -> "content"
            else -> "idle"
        }
}

object PetStore {
    private const val PREFS = "hoshio_pet"
    const val BY_HER = "her"
    const val BY_HIM = "him"
    val APPEARANCE_HINT: String get() = PetCatalog.appearances.joinToString(", ") { "${it.id} (${it.label})" }
    val DECORATIONS = linkedMapOf("sage" to "鼠尾草綠", "rose" to "玫瑰粉", "sky" to "天空藍")
    val ACTIONS = linkedMapOf(
        "feed" to "🍖 餵食", "water" to "💧 加水", "pet" to "🤲 摸摸", "play" to "🧶 玩耍",
        "tidy" to "🧹 整理", "sleep" to "😴 哄睡",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _pet = MutableStateFlow<Pet?>(null)
    val pet: StateFlow<Pet?> = _pet.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun advance(p: Pet, now: Long = System.currentTimeMillis()): Pet {
        val hours = ((now - p.updatedAt) / 3_600_000.0).coerceAtLeast(0.0)
        if (hours <= 0.0) return p
        val food = if (p.boarding) maxOf(80.0, p.fullness.toDouble()) else (p.fullness - 4 * hours).coerceAtLeast(0.0)
        val water = if (p.boarding) maxOf(80.0, p.water.toDouble()) else (p.water - 5 * hours).coerceAtLeast(0.0)
        val moodRate = if (p.boarding) .5 else 2.0
        val mood = (p.happiness - moodRate * hours).coerceAtLeast(0.0)
        val healthyHours = minOf(hours, p.happiness / moodRate, if (p.boarding) Double.POSITIVE_INFINITY else minOf(p.fullness / 4.0, p.water / 5.0))
        val health = ((p.health + healthyHours * 2).coerceAtMost(100.0) - (hours - healthyHours) * 3).coerceIn(0.0, 100.0)
        return p.copy(fullness = food.toInt(), water = water.toInt(), happiness = mood.toInt(), health = health.toInt(), updatedAt = now)
    }

    /** 读取并套用经过的时间（不写回，避免反复四舍五入） */
    fun load(context: Context): Pet? {
        val stored = prefs(context).getString("pet", null)?.let { runCatching { petJson.decodeFromString<Pet>(it) }.getOrNull() }
        val pet = stored?.let { advance(it) }
        _pet.value = pet
        return pet
    }

    private fun save(context: Context, pet: Pet?) {
        prefs(context).edit { if (pet == null) remove("pet") else putString("pet", petJson.encodeToString(pet)) }
        _pet.value = pet
        me.rerere.rikkahub.hoshio.widget.HoshioWidget.refresh(context)
    }

    fun adopt(context: Context, name: String, appearance: String, by: String): Pet {
        val look = PetCatalog.find(appearance) ?: PetCatalog.appearances.first()
        val pet = Pet(
            name = name.take(20).ifBlank { "小星" },
            species = when (look.family) { "dog" -> "🐶"; "bird" -> "🐦"; "reptile" -> "🦎"; else -> "🐱" },
            appearance = look.id,
            log = listOf(PetLog(by = by, text = "把${look.label}帶回家了")),
        )
        save(context, pet)
        return pet
    }

    fun rename(context: Context, name: String, by: String): Pet? {
        val pet = load(context) ?: return null
        val updated = pet.copy(name = name.take(20).ifBlank { pet.name }, log = (listOf(PetLog(by = by, text = "取名叫「${name.take(20)}」")) + pet.log).take(80))
        save(context, updated)
        return updated
    }

    /** 旧的 emoji 宠物换成有图的外观 */
    fun changeAppearance(context: Context, appearance: String, by: String): Pet? {
        val pet = load(context) ?: return null
        val look = PetCatalog.find(appearance) ?: return pet
        val updated = pet.copy(appearance = look.id, log = (listOf(PetLog(by = by, text = "換成了${look.label}的樣子")) + pet.log).take(80))
        save(context, updated)
        return updated
    }

    fun release(context: Context) = save(context, null)

    /** 從別處搬來的寵物（例如 Orbis 搬家），直接放進來 */
    fun restore(context: Context, pet: Pet) = save(context, pet.copy(updatedAt = System.currentTimeMillis()))

    data class CareResult(val pet: Pet, val rewardEarned: Boolean)

    /** action: feed | water | pet | play | tidy | sleep | board | return | decorate */
    fun care(context: Context, action: String, by: String, note: String? = null, decoration: String? = null): CareResult? {
        val old = load(context) ?: return null
        val now = System.currentTimeMillis()
        var next = when (action) {
            "feed" -> old.copy(fullness = (old.fullness + 25).coerceAtMost(100))
            "water" -> old.copy(water = (old.water + 30).coerceAtMost(100))
            "pet" -> old.copy(happiness = (old.happiness + 10).coerceAtMost(100), sleepingUntil = 0)
            "play" -> old.copy(
                happiness = (old.happiness + 20).coerceAtMost(100),
                fullness = (old.fullness - 2).coerceAtLeast(0),
                water = (old.water - 3).coerceAtLeast(0),
                messy = true,
                sleepingUntil = 0,
            )
            "tidy", "clean" -> old.copy(messy = false, cleanliness = 100)
            "sleep" -> old.copy(sleepingUntil = now + 3_600_000)
            "board" -> old.copy(boarding = true, fullness = maxOf(80, old.fullness), water = maxOf(80, old.water))
            "return" -> old.copy(boarding = false)
            "decorate" -> old.copy(decoration = decoration?.takeIf { it in DECORATIONS } ?: old.decoration)
            else -> return CareResult(old, false)
        }
        // 四项都够好时照顾，算一天；每满 10 天得一次奖励（漏掉的日子不会扣掉已得的）
        var reward = false
        if (action in setOf("feed", "water", "pet", "play") && minOf(next.fullness, next.water, next.happiness, next.health) >= 80) {
            val day = LocalDate.now().toString()
            if (day !in old.careDays) {
                val days = old.careDays + day
                reward = days.size % 10 == 0
                next = next.copy(careDays = days, rewards = old.rewards + if (reward) 1 else 0)
            }
        }
        val text = note?.take(60)?.ifBlank { null } ?: when (action) {
            "feed" -> "餵了牠"
            "water" -> "幫牠加水"
            "pet" -> "摸摸牠"
            "play" -> "陪牠玩，房間變亂了"
            "tidy", "clean" -> "整理了房間"
            "sleep" -> "哄牠睡覺"
            "board" -> "把牠送去寄養"
            "return" -> "把牠接回家"
            else -> "換了房間的顏色"
        }
        val withLog = next.copy(updatedAt = now, log = (listOf(PetLog(by = by, text = text)) + old.log).take(80))
        save(context, withLog)
        if (reward) onReward(context, withLog, by)
        return CareResult(withLog, reward)
    }

    /** 每满 10 天：成就挂上藏宝地图的成就墙，她照顾的话也告诉他 */
    private fun onReward(context: Context, pet: Pet, by: String) {
        val days = pet.careDays.size
        runCatching {
            val now = System.currentTimeMillis()
            me.rerere.rikkahub.hoshio.treasure.TreasureMapStore.update(context) { s ->
                s.copy(
                    badges = s.badges + me.rerere.rikkahub.hoshio.treasure.AwardedBadge(
                        "badge_pet_${now.toString(36)}",
                        me.rerere.rikkahub.hoshio.treasure.TreasureBadge("${pet.name}的好主人 ×${pet.rewards}", pet.species, "一起把${pet.name}好好照顧了 $days 天"),
                        "星汐",
                        now,
                    ),
                )
            }
        }
        if (by == BY_HER) {
            scope.launch {
                runCatching {
                    me.rerere.rikkahub.hoshio.HoshioChat.post(
                        "照顧獎勵",
                        "Automatic event: you two have now taken good care of ${pet.name} for $days days (reward #${pet.rewards}). " +
                            "A badge was added to her achievement wall. Celebrate with her in character, briefly.",
                    )
                }
            }
        }
    }

    /** 给模型看的一行 */
    fun describe(context: Context): String? {
        val p = load(context) ?: return null
        val lastByHim = p.log.firstOrNull { it.by == BY_HIM }?.at
        val look = PetCatalog.find(p.appearance)?.label ?: p.species
        return "$look ${p.name} (${p.ageDays} days old): ${p.status}; food ${p.fullness}, water ${p.water}, mood ${p.happiness}, health ${p.health}" +
            (if (p.messy) ", room messy" else "") + (if (p.boarding) ", boarding" else "") +
            "; good-care days ${p.careDays.size} (reward every 10, earned ${p.rewards})" +
            (lastByHim?.let { "; you last cared for it ${(System.currentTimeMillis() - it) / 3_600_000}h ago" } ?: "")
    }
}
