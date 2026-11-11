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
    val autoTrigger: Boolean = true,
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
)

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
        val stored = prefs(context).getString("state", null)?.let { runCatching { bodyJson.decodeFromString<BodyState>(it) }.getOrNull() } ?: BodyState()
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
        if (def.dimensions.size > 16) return "at most 16 dimensions"
        if (def.rules.size > 64) return "at most 64 rules"
        val ids = def.dimensions.map { it.id }
        if (ids.distinct().size != ids.size) return "dimension ids must be unique"
        def.dimensions.forEach { d ->
            if (!safeId.matches(d.id)) return "dimension id '${d.id}' must be letters, digits, _ or -"
            if (d.label.isBlank() || d.label.length > 20) return "dimension label must be 1-20 characters"
            if (d.min >= d.max || d.baseline !in d.min..d.max) return "dimension '${d.id}': need min < max and baseline within range"
            if (d.maxDelta <= 0 || d.halfLifeMinutes !in 0.5..525600.0) return "dimension '${d.id}': maxDelta > 0 and halfLifeMinutes 0.5-525600"
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
    fun design(context: Context, def: BodyDefinition): String? {
        validate(def)?.let { return it }
        update(context) { s ->
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

    /** 给他看的状态（含私密维度），并把还没告诉他的偷看纪录一并带上 */
    fun describeForHim(context: Context): String? {
        val s = load(context)
        if (s.definition.dimensions.isEmpty()) return null
        val dims = s.definition.dimensions.joinToString("; ") { d ->
            "${d.label}${if (d.isPrivate) "(private)" else ""} ${"%.0f".format(s.values[d.id] ?: d.baseline)}/${"%.0f".format(d.max)}"
        }
        val recent = s.logs.takeLast(3).joinToString("; ") { it.event }
        val untold = s.peeks.filter { !it.told }
        if (untold.isNotEmpty()) {
            update(context) { st -> st.copy(peeks = st.peeks.map { it.copy(told = true) }) }
        }
        val label = { id: String -> s.definition.dimensions.firstOrNull { it.id == id }?.label ?: id }
        return "body: $dims" +
            (if (recent.isNotBlank()) "; recent: $recent" else "") +
            (if (s.selfReport.isNotBlank()) "; your self-report: ${s.selfReport}" else "") +
            if (untold.isNotEmpty()) "; NOTE: she peeked at your private " + untold.joinToString(", ") { label(it.dimension) } else ""
    }
}
