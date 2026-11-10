package me.rerere.rikkahub.hoshio.life

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 星汐「她的心里话」：她在讯息里用 💭 开头的那几行，送给模型时会被藏起来，
 * 只留下「这则讯息附了一段心里话」的提示；他要用工具才看得到，看过会留下纪录。
 */
object HumanThoughts {
    private const val PREFS = "hoshio_human_thoughts"
    const val MARK = "💭"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 把文字拆成「说出口的」与「心里话」 */
    fun split(text: String): Pair<String, String?> {
        val lines = text.lines()
        val thought = lines.filter { it.trimStart().startsWith(MARK) }
            .joinToString("\n") { it.trimStart().removePrefix(MARK).trim() }
            .trim()
        if (thought.isEmpty()) return text to null
        val spoken = lines.filterNot { it.trimStart().startsWith(MARK) }.joinToString("\n").trim()
        return spoken to thought
    }

    @Synchronized
    fun save(context: Context, id: String, thought: String) {
        val p = prefs(context)
        if (p.getString("t_$id", null) != thought) p.edit { putString("t_$id", thought) }
    }

    @Synchronized
    fun read(context: Context, id: String): String? {
        val p = prefs(context)
        val thought = p.getString("t_$id", null) ?: return null
        p.edit { putLong("peek_$id", System.currentTimeMillis()) }
        return thought
    }

    fun peekedAt(context: Context, id: String): Long? = prefs(context).getLong("peek_$id", 0L).takeIf { it > 0 }
}
