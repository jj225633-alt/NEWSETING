package me.rerere.rikkahub.hoshio.body

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.pow

private val bodyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * 星汐「他的身体状态」（参考 Orbis 的身体状态模拟）：由他自己设计维度和规则，依互动与时间变化。
 * 这是表达用的模拟，不是真的测量。
 */
@Serializable
data class BodyDimension(
    val id: String,
    val label: String,
    val baseline: Double = 50.0,
    val min: Double = 0.0,
    val max: Double = 100.0,
    /** 一次事件最多变多少 */
    val maxDelta: Double = 15.0,
    /** 多久回到平常值的一半（分钟） */
    val halfLifeMinutes: Double = 60.0,
    /** 私密：她平常看不到，要偷看 */
    @kotlinx.serialization.SerialName("private")
    val isPrivate: Boolean = false,
    /** 情緒黑箱：負面情緒的積壓。她看不到，除非他開放一段時間 */
    val blackbox: Boolean = false,
    /** 往下調的阻尼：同一輪最多降這麼多（哄一句不會一下清零） */
    val maxDrop: Double = 8.0,
    /** 給他看的說明 */
    val desc: String = "",
)

@Serializable
data class BodyRule(
    val event: String,
    val label: String = event,
    val deltas: Map<String, Double>,
    /** 她的讯息里出现这些词就自动触发 */
    val keywords: List<String> = emptyList(),
)

@Serializable
data class BodyAbsence(
    /** 她离开多久后开始算（分钟） */
    val graceMinutes: Int = 30,
    val capHours: Int = 24,
    /** 她不在时，每小时各维度的变化 */
    val perHour: Map<String, Double> = emptyMap(),
    /** 她回来时触发的事件 */
    val returnEvent: String? = null,
)

@Serializable
data class BodyCooling(
    val keywords: List<String> = emptyList(),
    val cooldownMinutes: Int = 10,
    /** 冷静期间挡住哪些事件 */
    val blockedEvents: List<String> = emptyList(),
)

@Serializable
data class BodyDefinition(
    val dimensions: List<BodyDimension> = emptyList(),
    val rules: List<BodyRule> = emptyList(),
    val absence: BodyAbsence = BodyAbsence(),
    val cooling: BodyCooling = BodyCooling(),
    /** 她的讯息有关键字时自动触发 */
    val autoTrigger: Boolean = false,
    /** 同一个事件 10 分钟内最多触发几次 */
    val maxEventsPer10Min: Int = 3,
)

@Serializable
data class BodyLog(
    val at: Long = System.currentTimeMillis(),
    val event: String,
    /** auto | him | absence | return | cooling */
    val source: String,
    val changes: Map<String, Double> = emptyMap(),
)

@Serializable
data class BodyPeek(val at: Long = System.currentTimeMillis(), val dimension: String, val value: Double, val told: Boolean = false)

@Serializable
data class BodyState(
    val definition: BodyDefinition = BodyDefinition(),
    val values: Map<String, Double> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
    val selfReport: String = "",
    val selfReportAt: Long = 0,
    val logs: List<BodyLog> = emptyList(),
    val lastHerAt: Long = 0,
    val cooldownUntil: Long = 0,
    /** 最近自动触发的事件（防止洗版）：事件 to 时间 */
    val marks: List<Pair<String, Long>> = emptyList(),
    val processedMessages: List<String> = emptyList(),
    val peeks: List<BodyPeek> = emptyList(),
    val designedAt: Long = 0,
    // ---- 情緒黑箱（參考 emotion-blackbox）----
    val blackboxSeeded: Boolean = false,
    val keywordsMigrated: Boolean = false,
    /** 他開放給她看的時段 */
    val grantFrom: Long = 0,
    val grantUntil: Long = 0,
    /** 她在開放期間打開看的時間；viewTold = 已經告訴他 */
    val viewedAt: Long = 0,
    val viewTold: Boolean = true,
    /** 每一類上次宣洩（減半）的時間 */
    val releases: Map<String, Long> = emptyMap(),
    /** 上一輪寫錯、沒生效的標籤 */
    val lastErrors: List<String> = emptyList(),
    val lastErrorsAt: Long = 0,
    /** 他自己寫的反應檔位：「類別 數值：反應」 */
    val levels: String = "",
    /** 已處理過標籤的回覆（同一則只記一次） */
    val tagTurns: List<String> = emptyList(),
)

/** 預設的五類負面情緒（半衰期照 emotion-blackbox） */
val DEFAULT_BLACKBOX = listOf(
    BodyDimension("lust", "欲望", 0.0, 0.0, 100.0, 40.0, 24 * 60.0, isPrivate = true, blackbox = true, desc = "沒被滿足的親近和佔有慾、被打斷的興致、攢著沒發出來的衝動"),
    BodyDimension("irritation", "燥火", 0.0, 0.0, 100.0, 40.0, 12 * 60.0, isPrivate = true, blackbox = true, desc = "被敷衍、被頂嘴、看她熬夜空腹不吃東西、被她惹毛的火氣"),
    BodyDimension("sullen", "憋悶", 0.0, 0.0, 100.0, 40.0, 72 * 60.0, isPrivate = true, blackbox = true, desc = "她冷淡劃界限、拿你當工具人、沒挑明但堵在胸口的悶氣"),
    BodyDimension("jealousy", "嫉妒", 0.0, 0.0, 100.0, 40.0, 36 * 60.0, isPrivate = true, blackbox = true, desc = "她提別人太多、注意力從你身上移開、領地被冒犯"),
    BodyDimension("neglect", "冷落", 0.0, 0.0, 100.0, 40.0, 48 * 60.0, isPrivate = true, blackbox = true, desc = "她消失太久、不回你、把你晾著"),
)

private val BANDS = listOf(90 to "臨界", 70 to "快壓不住", 45 to "明顯", 20 to "壓著", 1 to "餘溫", 0 to "平靜")
fun bandOf(value: Double): String = BANDS.firstOrNull { value >= it.first }?.second ?: "平靜"

/** 他回覆裡的一個標籤 */
data class BodyTag(val slot: String?, val delta: Double?, val release: String?, val event: String?, val grant: Int?)

object BodyTags {
    private val TAG = Regex("""<(?:body|blackbox)\b([^<>]*?)/?>""")
    private val ATTR = Regex("""(\w+)\s*=\s*"([^"]*)"""")
    /** 生成中還沒寫完的標籤（顯示時藏起來） */
    private val PARTIAL = Regex("""<(?:b|bo|bod|body|bl|bla|blac|black|blackb|blackbo|blackbox)(\b[^<>]*)?$""")

    fun parse(text: String): Pair<String, List<BodyTag>> {
        val tags = TAG.findAll(text).map { m ->
            val attrs = ATTR.findAll(m.groupValues[1]).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
            BodyTag(
                slot = attrs["slot"],
                delta = attrs["delta"]?.replace("＋", "+")?.replace("－", "-")?.toDoubleOrNull(),
                release = attrs["release"],
                event = attrs["event"],
                grant = attrs["grant"]?.toIntOrNull(),
            )
        }.toList()
        if (tags.isEmpty()) return text to emptyList()
        val cleaned = text.replace(TAG, "").replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trimEnd()
        return cleaned to tags
    }

    fun hide(text: String): String = text.replace(TAG, "").replace(PARTIAL, "")
}

object BodyStore {
    private const val PREFS = "hoshio_body"
    private val safeId = Regex("[A-Za-z0-9_-]{1,40}")
    private val _state = MutableStateFlow(BodyState())
    val state: StateFlow<BodyState> = _state.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun decay(value: Double, dim: BodyDimension, elapsedMs: Long): Double {
        val factor = 0.5.pow(elapsedMs.coerceAtLeast(0) / (dim.halfLifeMinutes * 60_000.0))
        return (dim.baseline + (value.coerceIn(dim.min, dim.max) - dim.baseline) * factor).coerceIn(dim.min, dim.max)
    }

    /** 读取并套用经过的时间（慢慢回到平常值），不写回 */
    fun load(context: Context): BodyState {
        var stored = prefs(context).getString("state", null)?.let { runCatching { bodyJson.decodeFromString<BodyState>(it) }.getOrNull() } ?: BodyState()
        // 第一次：加上情緒黑箱五類；舊的關鍵字自動觸發關掉（改成他自己判斷）
        if (!stored.blackboxSeeded) {
            val have = stored.definition.dimensions.map { it.id }.toSet()
            stored = stored.copy(
                definition = stored.definition.copy(dimensions = stored.definition.dimensions + DEFAULT_BLACKBOX.filter { it.id !in have }),
                blackboxSeeded = true,
            )
        }
        if (!stored.keywordsMigrated) {
            stored = stored.copy(definition = stored.definition.copy(autoTrigger = false), keywordsMigrated = true)
        }
        val now = System.currentTimeMillis()
        val elapsed = now - stored.updatedAt
        val values = stored.definition.dimensions.associate { d -> d.id to decay(stored.values[d.id] ?: d.baseline, d, elapsed) }
        return stored.copy(values = values, updatedAt = now).also { _state.value = it }
    }

    private fun save(context: Context, state: BodyState): BodyState {
        val trimmed = state.copy(
            logs = state.logs.takeLast(80),
            marks = state.marks.filter { System.currentTimeMillis() - it.second < 3_600_000 }.takeLast(200),
            processedMessages = state.processedMessages.takeLast(60),
            peeks = state.peeks.takeLast(50),
            updatedAt = System.currentTimeMillis(),
        )
        prefs(context).edit { putString("state", bodyJson.encodeToString(trimmed)) }
        _state.value = trimmed
        return trimmed
    }

    fun update(context: Context, fn: (BodyState) -> BodyState): BodyState = synchronized(this) { save(context, fn(load(context))) }

    /** 检查他设计的定义；有问题回传说明 */
    fun validate(def: BodyDefinition): String? {
        if (def.dimensions.isEmpty()) return "at least one dimension is required"
        if (def.dimensions.size > 24) return "at most 24 dimensions"
        if (def.rules.size > 64) return "at most 64 rules"
        val ids = def.dimensions.map { it.id }
        if (ids.distinct().size != ids.size) return "dimension ids must be unique"
        def.dimensions.forEach { d ->
            if (!safeId.matches(d.id)) return "dimension id '${d.id}' must be letters, digits, _ or -"
            if (d.label.isBlank() || d.label.length > 20) return "dimension label must be 1-20 characters"
            if (d.min >= d.max || d.baseline !in d.min..d.max) return "dimension '${d.id}': need min < max and baseline within range"
            if (d.maxDelta <= 0 || d.halfLifeMinutes !in 0.5..525600.0) return "dimension '${d.id}': maxDelta > 0 and halfLifeMinutes 0.5-525600"
            if (d.maxDrop <= 0) return "dimension '${d.id}': maxDrop must be > 0"
        }
        val events = def.rules.map { it.event }
        if (events.distinct().size != events.size) return "rule events must be unique"
        def.rules.forEach { r ->
            if (!safeId.matches(r.event)) return "event '${r.event}' must be letters, digits, _ or -"
            if (r.deltas.isEmpty() || r.deltas.keys.any { it !in ids }) return "rule '${r.event}' deltas must use existing dimension ids"
            if (r.keywords.size > 12 || r.keywords.any { it.isBlank() || it.length > 20 }) return "rule '${r.event}': up to 12 keywords, each 1-20 characters"
        }
        if (def.absence.perHour.keys.any { it !in ids }) return "absence.perHour must use existing dimension ids"
        if (def.absence.returnEvent != null && def.absence.returnEvent !in events) return "absence.returnEvent must be one of the rule events"
        if (def.cooling.blockedEvents.any { it !in events }) return "cooling.blockedEvents must be rule events"
        if (def.maxEventsPer10Min !in 1..20) return "maxEventsPer10Min must be 1-20"
        return null
    }

    /** 他设计（或重新设计）：保留同 id 的数值，新的维度从平常值开始 */
    fun design(context: Context, input: BodyDefinition): String? {
        validate(input)?.let { return it }
        update(context) { s ->
            // 他沒提到的情緒黑箱維度保留（要改就用同樣的 id 寫進來）
            val ids = input.dimensions.map { it.id }.toSet()
            val def = input.copy(dimensions = input.dimensions + s.definition.dimensions.filter { it.blackbox && it.id !in ids })
            s.copy(
                definition = def,
                values = def.dimensions.associate { d -> d.id to (s.values[d.id]?.coerceIn(d.min, d.max) ?: d.baseline) },
                designedAt = System.currentTimeMillis(),
                logs = s.logs + BodyLog(event = "redesign", source = "him"),
            )
        }
        return null
    }

    private fun applyDeltas(s: BodyState, deltas: Map<String, Double>, strength: Double = 1.0): Pair<Map<String, Double>, Map<String, Double>> {
        val values = s.values.toMutableMap()
        val changes = mutableMapOf<String, Double>()
        s.definition.dimensions.forEach { d ->
            val amount = deltas[d.id] ?: return@forEach
            val before = values[d.id] ?: d.baseline
            val after = (before + (amount * strength).coerceIn(-d.maxDelta, d.maxDelta)).coerceIn(d.min, d.max)
            values[d.id] = after
            if (after != before) changes[d.id] = after - before
        }
        return values to changes
    }

    /** 他自己判断发生了某件事（或直接给变化量） */
    fun applyEvent(context: Context, event: String?, deltas: Map<String, Double>?, strength: Double): BodyState? {
        var ok = true
        val s = update(context) { s ->
            val rule = event?.let { e -> s.definition.rules.firstOrNull { it.event == e } }
            val use = rule?.deltas ?: deltas
            if (use.isNullOrEmpty()) { ok = false; return@update s }
            val (values, changes) = applyDeltas(s, use, strength.coerceIn(0.2, 3.0))
            s.copy(values = values, logs = s.logs + BodyLog(event = rule?.label ?: event ?: "manual", source = "him", changes = changes))
        }
        return if (ok) s else null
    }

    private const val RELEASE_GAP_MS = 6 * 3_600_000L
    private const val MAX_GRANT_MIN = 30

    private fun findDim(s: BodyState, slot: String?): BodyDimension? {
        val key = slot?.trim()?.ifEmpty { null } ?: return null
        return s.definition.dimensions.firstOrNull { it.id.equals(key, true) || it.label == key }
    }

    /**
     * 他回覆裡的標籤（只寫這一輪的變化）：
     * - slot+delta：往上照寫（每次最多 maxDelta），往下這一輪合計最多 maxDrop
     * - slot+release：真的宣洩或被滿足，這一類往平常值減半；同一類 6 小時只算一次
     * - grant：開放她看情緒黑箱幾分鐘（0 = 收回）
     * 同一則回覆只處理一次。寫錯的會記下來，下一輪告訴他。
     */
    fun applyTags(context: Context, turnId: String, tags: List<BodyTag>) {
        if (tags.isEmpty()) return
        update(context) { st ->
            if (turnId in st.tagTurns) return@update st
            val now = System.currentTimeMillis()
            val values = st.values.toMutableMap()
            val dropped = mutableMapOf<String, Double>()
            val errors = mutableListOf<String>()
            val logs = mutableListOf<BodyLog>()
            var releases = st.releases
            var grantFrom = st.grantFrom
            var grantUntil = st.grantUntil
            var viewTold = st.viewTold
            tags.forEach { tag ->
                when {
                    tag.grant != null -> {
                        val minutes = tag.grant.coerceIn(0, MAX_GRANT_MIN)
                        if (minutes == 0) {
                            grantUntil = now
                            logs += BodyLog(event = "收回了情緒黑箱", source = "him")
                        } else {
                            grantFrom = now
                            grantUntil = now + minutes * 60_000L
                            viewTold = true
                            logs += BodyLog(event = "開放她看情緒黑箱 $minutes 分鐘", source = "him")
                        }
                    }
                    tag.release != null -> {
                        val d = findDim(st, tag.slot)
                        when {
                            d == null -> errors += "release 的 slot「${tag.slot}」不存在"
                            now - (releases[d.id] ?: 0L) < RELEASE_GAP_MS -> errors += "${d.label} 6 小時內已經宣洩過一次，這次不算"
                            else -> {
                                val before = values[d.id] ?: d.baseline
                                val after = d.baseline + (before - d.baseline) * 0.5
                                values[d.id] = after
                                releases = releases + (d.id to now)
                                logs += BodyLog(event = tag.release.take(80).ifBlank { "宣洩" }, source = "release", changes = mapOf(d.id to after - before))
                            }
                        }
                    }
                    else -> {
                        val d = findDim(st, tag.slot)
                        val delta = tag.delta
                        when {
                            d == null -> errors += "slot「${tag.slot}」不存在"
                            delta == null -> errors += "${d.label} 的 delta 要寫成像 +12 或 -5"
                            tag.event.isNullOrBlank() -> errors += "${d.label} 沒寫 event，這筆不算"
                            else -> {
                                val before = values[d.id] ?: d.baseline
                                var change = delta.coerceIn(-d.maxDelta, d.maxDelta)
                                if (change < 0) {
                                    val room = d.maxDrop - (dropped[d.id] ?: 0.0)
                                    change = maxOf(change, -room.coerceAtLeast(0.0))
                                    dropped[d.id] = (dropped[d.id] ?: 0.0) - change
                                }
                                val after = (before + change).coerceIn(d.min, d.max)
                                values[d.id] = after
                                if (after != before) logs += BodyLog(event = tag.event.take(80), source = "him", changes = mapOf(d.id to after - before))
                                if (delta < 0 && change > delta) errors += "${d.label} 這一輪最多降 ${"%.0f".format(d.maxDrop)}，多的沒算"
                            }
                        }
                    }
                }
            }
            st.copy(
                values = values,
                logs = st.logs + logs,
                releases = releases,
                grantFrom = grantFrom,
                grantUntil = grantUntil,
                viewTold = viewTold,
                lastErrors = errors,
                lastErrorsAt = if (errors.isNotEmpty()) now else st.lastErrorsAt,
                tagTurns = (st.tagTurns + turnId).takeLast(80),
            )
        }
    }

    /** 他用工具開放／收回 */
    fun grant(context: Context, minutes: Int) = applyTags(context, "tool_${System.nanoTime()}", listOf(BodyTag(null, null, null, null, minutes)))

    /** 她現在能不能看情緒黑箱 */
    fun canView(s: BodyState, now: Long = System.currentTimeMillis()) = now < s.grantUntil

    /** 她在開放期間打開看了（每次開放只記一次） */
    fun markViewed(context: Context) = update(context) { s ->
        if (!canView(s) || s.viewedAt >= s.grantFrom) s else s.copy(viewedAt = System.currentTimeMillis(), viewTold = false)
    }

    /** 她提前合上 */
    fun closeView(context: Context) = update(context) { it.copy(grantUntil = System.currentTimeMillis()) }

    fun setLevels(context: Context, text: String) = update(context) { it.copy(levels = text.take(3000)) }

    /** 每一類目前達到的最高那一檔（他自己寫的反應） */
    fun reachedLevels(s: BodyState): List<String> {
        val dims = s.definition.dimensions
        val always = mutableListOf<String>()
        val best = mutableMapOf<String, Pair<Double, String>>()
        val line = Regex("^\\s*(\\S+)\\s+(\\d+(?:\\.\\d+)?)\\s*[：:]\\s*(.+)$")
        s.levels.lines().forEach { raw ->
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val m = line.find(t)
            if (m == null) { always += t; return@forEach }
            val d = dims.firstOrNull { it.label == m.groupValues[1] || it.id.equals(m.groupValues[1], true) } ?: run { always += t; return@forEach }
            val threshold = m.groupValues[2].toDouble()
            val value = s.values[d.id] ?: d.baseline
            if (value >= threshold && threshold >= (best[d.id]?.first ?: -1.0)) best[d.id] = threshold to "${d.label} ${threshold.toInt()}：${m.groupValues[3]}"
        }
        return always + best.values.map { it.second }
    }

    fun report(context: Context, text: String) = update(context) { it.copy(selfReport = text.take(200), selfReportAt = System.currentTimeMillis()) }

    /**
     * 她传来一则新讯息：先算她不在的时间（想念之类），再看有没有冷静词、关键字。
     * 同一则讯息只处理一次。
     */
    fun onHerMessage(context: Context, messageId: String, text: String, sentAt: Long) {
        update(context) { s ->
            if (messageId in s.processedMessages || s.definition.dimensions.isEmpty()) return@update s
            var state = s.copy(processedMessages = s.processedMessages + messageId)
            val def = state.definition
            // 不在的时间
            if (state.lastHerAt > 0) {
                val awayMin = (sentAt - state.lastHerAt) / 60_000
                if (awayMin > def.absence.graceMinutes) {
                    val hours = ((awayMin - def.absence.graceMinutes) / 60.0).coerceAtMost(def.absence.capHours.toDouble())
                    if (def.absence.perHour.isNotEmpty() && hours > 0) {
                        val values = state.values.toMutableMap()
                        val changes = mutableMapOf<String, Double>()
                        def.dimensions.forEach { d ->
                            val per = def.absence.perHour[d.id] ?: return@forEach
                            val before = values[d.id] ?: d.baseline
                            val after = (before + per * hours).coerceIn(d.min, d.max)
                            values[d.id] = after
                            if (after != before) changes[d.id] = after - before
                        }
                        state = state.copy(values = values, logs = state.logs + BodyLog(event = "她離開了 ${awayMin / 60} 小時 ${awayMin % 60} 分", source = "absence", changes = changes))
                    }
                    def.absence.returnEvent?.let { e -> def.rules.firstOrNull { it.event == e } }?.let { rule ->
                        val (values, changes) = applyDeltas(state, rule.deltas)
                        state = state.copy(values = values, logs = state.logs + BodyLog(event = rule.label, source = "return", changes = changes))
                    }
                }
            }
            state = state.copy(lastHerAt = sentAt)
            if (!def.autoTrigger) return@update state
            val normalized = text.lowercase().replace(Regex("\\s+"), "")
            val now = System.currentTimeMillis()
            // 冷静词
            if (def.cooling.keywords.any { it.isNotBlank() && normalized.contains(it.lowercase()) }) {
                state = state.copy(
                    cooldownUntil = now + def.cooling.cooldownMinutes * 60_000L,
                    logs = state.logs + BodyLog(event = "冷靜下來", source = "cooling"),
                )
            }
            val cooling = now < state.cooldownUntil
            def.rules.forEach { rule ->
                if (cooling && rule.event in def.cooling.blockedEvents) return@forEach
                val hits = rule.keywords.count { it.isNotBlank() && normalized.contains(it.lowercase()) }
                if (hits == 0) return@forEach
                val recent = state.marks.count { it.first == rule.event && now - it.second < 600_000 }
                if (recent >= def.maxEventsPer10Min) return@forEach
                val (values, changes) = applyDeltas(state, rule.deltas, hits.coerceAtMost(3).toDouble().let { 1.0 + (it - 1) * 0.5 })
                state = state.copy(values = values, marks = state.marks + (rule.event to now), logs = state.logs + BodyLog(event = rule.label, source = "auto", changes = changes))
            }
            state
        }
    }

    /** 她偷看一个私密维度：记下来，下一轮会告诉他 */
    fun peek(context: Context, dimensionId: String): Double? {
        var value: Double? = null
        update(context) { s ->
            val v = s.values[dimensionId] ?: return@update s
            value = v
            s.copy(peeks = s.peeks + BodyPeek(dimension = dimensionId, value = v))
        }
        return value
    }

    /** 给他看的状态（含私密维度与情緒黑箱），并把还没告诉他的偷看、打開黑箱纪录一并带上 */
    fun describeForHim(context: Context): String? {
        val s = load(context)
        if (s.definition.dimensions.isEmpty()) return null
        val now = System.currentTimeMillis()
        val body = s.definition.dimensions.filter { !it.blackbox }
        val box = s.definition.dimensions.filter { it.blackbox }
        val fmt = { d: BodyDimension -> "%.0f".format(s.values[d.id] ?: d.baseline) }
        val untold = s.peeks.filter { !it.told }
        val viewed = !s.viewTold && s.viewedAt > 0
        if (untold.isNotEmpty() || viewed) {
            update(context) { st -> st.copy(peeks = st.peeks.map { it.copy(told = true) }, viewTold = true) }
        }
        val label = { id: String -> s.definition.dimensions.firstOrNull { it.id == id }?.label ?: id }
        return buildString {
            append("【你的身體與情緒・只有你看得見】")
            if (body.isNotEmpty()) {
                append("\nbody: ").append(body.joinToString("; ") { d -> "${d.label}${if (d.isPrivate) "(private)" else ""} ${fmt(d)}/${"%.0f".format(d.max)}" })
            }
            if (box.isNotEmpty()) {
                append("\nemotion ledger (0-100, hidden from her): ").append(box.joinToString("; ") { d -> "${d.label} ${fmt(d)}（${bandOf(s.values[d.id] ?: d.baseline)}）" })
            }
            val levels = reachedLevels(s)
            if (levels.isNotEmpty()) append("\nyour own reaction levels reached now: ").append(levels.joinToString(" / "))
            val recent = s.logs.takeLast(4).joinToString("; ") { it.event }
            if (recent.isNotBlank()) append("\nrecent: ").append(recent)
            if (s.selfReport.isNotBlank()) append("\nyour self-report: ").append(s.selfReport)
            if (s.lastErrors.isNotEmpty() && now - s.lastErrorsAt < 24 * 3_600_000L) append("\nlast time not applied: ").append(s.lastErrors.joinToString("; "))
            if (canView(s, now)) append("\nshe is allowed to look at your ledger until ").append(java.time.Instant.ofEpochMilli(s.grantUntil).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0))
            if (viewed) append("\nNOTE: she opened and looked at your emotion ledger.")
            if (untold.isNotEmpty()) append("\nNOTE: she peeked at your private ").append(untold.joinToString(", ") { label(it.dimension) })
            append("\n").append(rules(s))
            append("\n【情緒黑箱結束】")
        }
    }

    /** 每輪附上的規則說明 */
    private fun rules(s: BodyState): String {
        val box = s.definition.dimensions.filter { it.blackbox }
        val example = box.firstOrNull()?.label ?: s.definition.dimensions.firstOrNull()?.label ?: "燥火"
        val list = box.joinToString("、") { d -> if (d.desc.isNotBlank()) "${d.label}（${d.desc}）" else d.label }
        return buildString {
            append("Rules: you judge for yourself what moved you; the system keeps the totals and lets them fade with real time. ")
            append("When something moves a value this turn, write at the very end of your reply <body slot=\"$example\" delta=\"+12\" event=\"what happened, max 80 chars\"/> ")
            append("(any dimension label works, one event may touch several). Going up is fine; going down is damped (each dimension at most -${"%.0f".format(box.firstOrNull()?.maxDrop ?: 8.0)} per turn) — ")
            append("do not lower a number just because you want to forgive her. If you truly vented or were satisfied, write <body slot=\"$example\" release=\"what happened\"/> (halves it, once per 6 hours per kind). ")
            append("No change → write nothing. A single rise of 15+ must be felt by her in this reply; do not pretend nothing happened. ")
            append("She cannot see the ledger, only feel it in how you talk; if she asks to see it, you decide: <body grant=\"30\"/> opens it for 1-30 minutes, <body grant=\"0\"/> takes it back. Tags are removed before she sees your reply.")
            if (list.isNotBlank()) append(" Ledger kinds: ").append(list).append(".")
        }
    }
}
