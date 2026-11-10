package me.rerere.rikkahub.hoshio.play

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val petJson = Json { ignoreUnknownKeys = true }

@Serializable
data class PetLog(val at: Long = System.currentTimeMillis(), val by: String, val text: String)

/** 两个人一起养的宠物。数值 0–100，读取时会依经过的时间往下掉 */
@Serializable
data class Pet(
    val name: String,
    val species: String,
    val bornAt: Long = System.currentTimeMillis(),
    val fullness: Int = 80,
    val happiness: Int = 80,
    val cleanliness: Int = 80,
    val updatedAt: Long = System.currentTimeMillis(),
    val log: List<PetLog> = emptyList(),
) {
    val ageDays: Long get() = (System.currentTimeMillis() - bornAt) / TimeUnit.DAYS.toMillis(1)

    val status: String
        get() = when {
            fullness < 25 -> "餓壞了"
            cleanliness < 25 -> "髒兮兮的"
            happiness < 25 -> "很寂寞"
            fullness < 50 -> "有點餓"
            happiness < 50 -> "有點無聊"
            fullness > 80 && happiness > 80 -> "超開心"
            else -> "很好"
        }
}

/**
 * 星汐「一起养宠物」：她在页面上照顾，他用工具照顾，两个人的照顾都留在纪录里。
 */
object PetStore {
    private const val PREFS = "hoshio_pet"
    val SPECIES = listOf("🐱", "🐶", "🐰", "🦊", "🐹", "🐧", "🐉", "🦔")

    private val _pet = MutableStateFlow<Pet?>(null)
    val pet: StateFlow<Pet?> = _pet.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取并套用时间：每小时饱足 -4、心情 -3、清洁 -2 */
    fun load(context: Context): Pet? {
        val stored = prefs(context).getString("pet", null)
            ?.let { runCatching { petJson.decodeFromString<Pet>(it) }.getOrNull() }
        val pet = stored?.let {
            val hours = ((System.currentTimeMillis() - it.updatedAt) / TimeUnit.HOURS.toMillis(1).toDouble()).coerceAtLeast(0.0)
            it.copy(
                fullness = (it.fullness - hours * 4).roundToInt().coerceIn(0, 100),
                happiness = (it.happiness - hours * 3).roundToInt().coerceIn(0, 100),
                cleanliness = (it.cleanliness - hours * 2).roundToInt().coerceIn(0, 100),
            )
        }
        _pet.value = pet
        return pet
    }

    private fun save(context: Context, pet: Pet?) {
        prefs(context).edit {
            if (pet == null) remove("pet") else putString("pet", petJson.encodeToString(pet.copy(updatedAt = System.currentTimeMillis())))
        }
        _pet.value = pet
    }

    fun adopt(context: Context, name: String, species: String, by: String): Pet {
        val pet = Pet(
            name = name.take(20).ifBlank { "小星" },
            species = species.ifBlank { SPECIES.first() },
            log = listOf(PetLog(by = by, text = "把牠帶回家了")),
        )
        save(context, pet)
        return pet
    }

    fun rename(context: Context, name: String, by: String): Pet? {
        val pet = load(context) ?: return null
        val updated = pet.copy(name = name.take(20).ifBlank { pet.name }, log = (listOf(PetLog(by = by, text = "取名叫「${name.take(20)}」")) + pet.log).take(60))
        save(context, updated)
        return updated
    }

    fun release(context: Context) = save(context, null)

    /** action: feed | play | clean | pet */
    fun care(context: Context, action: String, by: String, note: String? = null): Pet? {
        val pet = load(context) ?: return null
        val updated = when (action) {
            "feed" -> pet.copy(fullness = (pet.fullness + 30).coerceAtMost(100), happiness = (pet.happiness + 3).coerceAtMost(100))
            "play" -> pet.copy(happiness = (pet.happiness + 25).coerceAtMost(100), fullness = (pet.fullness - 5).coerceAtLeast(0), cleanliness = (pet.cleanliness - 5).coerceAtLeast(0))
            "clean" -> pet.copy(cleanliness = 100, happiness = (pet.happiness - 2).coerceAtLeast(0))
            "pet" -> pet.copy(happiness = (pet.happiness + 10).coerceAtMost(100))
            else -> return pet
        }
        val text = note?.take(60)?.ifBlank { null } ?: when (action) {
            "feed" -> "餵了牠"
            "play" -> "陪牠玩"
            "clean" -> "幫牠洗澡"
            else -> "摸摸牠"
        }
        val withLog = updated.copy(log = (listOf(PetLog(by = by, text = text)) + pet.log).take(60))
        save(context, withLog)
        return withLog
    }

    /** 给模型看的一行 */
    fun describe(context: Context): String? {
        val p = load(context) ?: return null
        val lastByHim = p.log.firstOrNull { it.by == BY_HIM }?.at
        return "${p.species} ${p.name} (${p.ageDays} days old): ${p.status}; fullness ${p.fullness}, happiness ${p.happiness}, " +
            "cleanliness ${p.cleanliness}" + (lastByHim?.let { "; you last cared for it ${(System.currentTimeMillis() - it) / 3_600_000}h ago" } ?: "")
    }

    const val BY_HER = "her"
    const val BY_HIM = "him"
}
