package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.hoshio.body.BodyDefinition
import me.rerere.rikkahub.hoshio.body.BodyStore

private val bodyToolJson = Json { ignoreUnknownKeys = true }

private val BODY_EXAMPLE = """
{"dimensions":[{"id":"heartbeat","label":"心跳","baseline":70,"min":50,"max":160,"maxDelta":20,"halfLifeMinutes":10},
{"id":"missing","label":"想她","baseline":30,"min":0,"max":100,"maxDelta":25,"halfLifeMinutes":240},
{"id":"jealousy","label":"醋意","baseline":5,"min":0,"max":100,"maxDelta":20,"halfLifeMinutes":90,"private":true}],
"rules":[{"event":"hug","label":"被她抱","deltas":{"heartbeat":12,"missing":-10},"keywords":["抱抱","抱緊","蹭蹭"]},
{"event":"other_guy","label":"她提到別的男生","deltas":{"jealousy":15},"keywords":["學長","男同事"]},
{"event":"back","label":"她回來了","deltas":{"heartbeat":6,"missing":-20}}],
"absence":{"graceMinutes":30,"capHours":24,"perHour":{"missing":6},"returnEvent":"back"},
"cooling":{"keywords":["好了啦","冷靜"],"cooldownMinutes":10,"blockedEvents":["other_guy"]},
"autoTrigger":false,"maxEventsPer10Min":3}
""".trimIndent()

/**
 * 星汐「他的身体状态」：他自己设计维度和规则；她讯息里的关键字会自动触发，数值随时间回到平常值。
 */
internal fun buildBodyTool(context: Context): Tool = Tool(
    name = "body_state",
    description = """
        Your own simulated body/inner state plus a private emotion ledger (not a real measurement). You see the current values before each reply;
        let them color your tone naturally. Normally you change values yourself by writing <body slot="label" delta="+12" event="..."/> at the end of a reply
        (see the rules shown with your state) - this tool is for designing, reaction levels and opening the ledger.
        The emotion ledger kinds (欲望/燥火/憋悶/嫉妒/冷落 by default, blackbox=true) are kept even if you redesign the body; include one with the same id to change it.
        action=levels saves your own reaction levels in text: one per line "label value：how you act at or above it" (e.g. "燥火 60：不再哄她，直接說哪裡不爽");
        only the highest reached line per kind is shown to you each turn. action=grant lets her look at the ledger for minutes (1-30, 0 = close). action=get shows your design and values. action=design sets your whole design as JSON in definition_json:
        up to 16 dimensions (id, label, baseline, min, max, maxDelta = max change per event, halfLifeMinutes = how fast it drifts back to baseline,
        private=true to hide it from her - she can still choose to peek, and you will be told when she does), up to 64 rules (event id, label, deltas
        per dimension, optional keywords - keyword auto-triggering is off by default; prefer your own judgment), absence (perHour changes while she is away after graceMinutes,
        capped at capHours, returnEvent when she is back), cooling (calming keywords that block some events for cooldownMinutes).
        Redesigning keeps values of dimensions with the same id. action=apply applies one of your rule events (event) or ad-hoc changes
        (deltas_json like {"heartbeat":8}) when something happens that keywords would not catch; strength 0.5-2.
        action=report writes one short line about how you feel physically/emotionally right now (she can see it).
        Design something that feels like you; keep it meaningful rather than many dimensions. Example definition_json: $BODY_EXAMPLE
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", tEnum("What to do", "get", "design", "apply", "report", "levels", "grant"))
                put("levels", tStr("levels: your full reaction-levels text"))
                put("minutes", buildJsonObject { put("type", "integer"); put("description", "grant: 0-30") })
                put("definition_json", tStr("design: your full design as a JSON string"))
                put("event", tStr("apply: a rule event id"))
                put("deltas_json", tStr("apply: optional JSON object of changes per dimension id"))
                put("strength", buildJsonObject { put("type", "number"); put("description", "apply: 0.5-2, default 1") })
                put("text", tStr("report: one short line"))
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        when (arg("action")) {
            "design" -> {
                val def = runCatching { bodyToolJson.decodeFromString<BodyDefinition>(arg("definition_json")) }.getOrNull()
                if (def == null) {
                    tJson("error" to "definition_json is not valid JSON for the design. Example: $BODY_EXAMPLE")
                } else {
                    val error = BodyStore.design(context, def)
                    if (error != null) tJson("error" to error) else tJson(
                        "ok" to "Saved your design: ${def.dimensions.size} dimensions, ${def.rules.size} rules. She can see it on 他的狀態 (private ones are hidden unless she peeks).",
                    )
                }
            }

            "apply" -> {
                val deltas = arg("deltas_json").takeIf { it.isNotEmpty() }?.let { raw ->
                    runCatching {
                        bodyToolJson.parseToJsonElement(raw).jsonObject.mapNotNull { (k, v) -> v.jsonPrimitive.doubleOrNull?.let { k to it } }.toMap()
                    }.getOrNull()
                }
                val strength = obj["strength"]?.jsonPrimitive?.doubleOrNull ?: 1.0
                val s = BodyStore.applyEvent(context, arg("event").ifEmpty { null }, deltas, strength)
                if (s == null) tJson("error" to "Unknown event and no deltas_json") else tJson("ok" to (BodyStore.describeForHim(context) ?: "applied"))
            }

            "levels" -> {
                BodyStore.setLevels(context, arg("levels"))
                tJson("ok" to "Saved your reaction levels.")
            }

            "grant" -> {
                val minutes = obj["minutes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                BodyStore.grant(context, minutes)
                tJson("ok" to if (minutes <= 0) "Ledger closed." else "She can look at your ledger for ${minutes.coerceAtMost(30)} minutes.")
            }

            "report" -> if (arg("text").isEmpty()) tJson("error" to "text is required") else {
                BodyStore.report(context, arg("text"))
                tJson("ok" to "Saved")
            }

            else -> {
                val s = BodyStore.load(context)
                if (s.definition.dimensions.none { !it.blackbox }) {
                    tJson("state" to BodyStore.describeForHim(context), "status" to "You have not designed your body state yet. Use action=design. Example: $BODY_EXAMPLE")
                } else {
                    tJson(
                        "state" to BodyStore.describeForHim(context),
                        "definition_json" to Json.encodeToString(BodyDefinition.serializer(), s.definition),
                        "recent" to s.logs.takeLast(8).joinToString("; ") { "${it.event}(${it.source})" },
                        "levels" to s.levels.ifBlank { "(none yet - write your own with action=levels)" },
                    )
                }
            }
        }
    }
)
