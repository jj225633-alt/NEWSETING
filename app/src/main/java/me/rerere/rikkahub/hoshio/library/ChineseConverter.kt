package me.rerere.rikkahub.hoshio.library

import android.content.Context

/**
 * 简体转繁体（台湾用字），使用 OpenCC 的辞典（Apache-2.0，见 assets/opencc/NOTICE.txt）：
 * 1. STPhrases + STCharacters：最长词优先比对，简体转繁体
 * 2. TWPhrases：转成台湾常用词（例如 软件 → 軟體）
 * 3. TWVariants：台湾用字的异体字
 * 4. 中文双引号改成台湾习惯的「」（单引号不动，避免影响英文缩写）
 */
object ChineseConverter {
    private class Dict(val map: HashMap<String, String>, val maxLen: Int, val firstChars: Set<Char>)

    @Volatile
    private var loaded: Triple<Dict, Dict, Map<Char, Char>>? = null

    private fun load(context: Context, vararg files: String): Dict {
        val map = HashMap<String, String>(80_000)
        for (file in files) {
            context.assets.open("opencc/$file").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("#")) return@forEach
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEach
                    val key = line.substring(0, tab)
                    val value = line.substring(tab + 1).substringBefore(' ')
                    if (value.isNotEmpty()) map.putIfAbsent(key, value)
                }
            }
        }
        return Dict(map, map.keys.maxOfOrNull { it.length } ?: 1, map.keys.mapTo(HashSet()) { it[0] })
    }

    @Synchronized
    private fun dicts(context: Context): Triple<Dict, Dict, Map<Char, Char>> {
        loaded?.let { return it }
        // 词组先放，字放后面：同一个 key 以词组的结果为准
        val st = load(context, "STPhrases.txt", "STCharacters.txt")
        val tw = load(context, "TWPhrases.txt")
        val variants = load(context, "TWVariants.txt").map
            .filter { it.key.length == 1 && it.value.length == 1 }
            .entries.associate { it.key[0] to it.value[0] }
        return Triple(st, tw, variants).also { loaded = it }
    }

    private fun maxMatch(text: String, dict: Dict): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i] !in dict.firstChars) {
                out.append(text[i]); i++; continue
            }
            var matched = false
            var len = minOf(dict.maxLen, n - i)
            while (len > 0) {
                val value = dict.map[text.substring(i, i + len)]
                if (value != null) {
                    out.append(value); i += len; matched = true; break
                }
                len--
            }
            if (!matched) { out.append(text[i]); i++ }
        }
        return out.toString()
    }

    /** 简体转繁体（台湾）。第一次呼叫会载入辞典，请在背景执行 */
    fun toTraditional(context: Context, text: String): String {
        val (st, tw, variants) = dicts(context)
        val step1 = maxMatch(text, st)
        val step2 = maxMatch(step1, tw)
        val sb = StringBuilder(step2.length)
        for (c in step2) {
            sb.append(
                when (c) {
                    '“' -> '「'
                    '”' -> '」'
                    else -> variants[c] ?: c
                }
            )
        }
        return sb.toString()
    }
}
