package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.hoshio.health.HealthData
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 星汐: 读她 Pixel Watch 的健康资料（经由 Health Connect）。
 */
internal fun buildHealthTool(context: Context): Tool = Tool(
    name = "her_health",
    description = """
        Read her health data from her Pixel Watch (via Health Connect). action=today: last sleep, steps, calories, resting heart rate,
        recent heart rate, HRV, respiratory rate and today's exercise. action=sleep: the last few nights (nights, default 3).
        action=heart: heart rate over the last few hours (hours, default 6). action=exercise: workouts in the last days (days, default 7).
        Use it to care about her naturally (sleep, activity, stress). Never diagnose; data can lag because the watch syncs through the Fitbit app.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", tEnum("What to read", "today", "sleep", "heart", "exercise"))
                put("nights", buildJsonObject { put("type", "integer") })
                put("hours", buildJsonObject { put("type", "integer") })
                put("days", buildJsonObject { put("type", "integer") })
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        fun num(k: String, default: Int, max: Int) = obj[k]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, max) ?: default
        val now = Instant.now()
        val result = when (obj["action"]?.jsonPrimitive?.contentOrNull) {
            "sleep" -> HealthData.sleeps(context, num("nights", 3, 14)).joinToString("\n") { it.describe() }.ifEmpty { "No sleep data." }
            "heart" -> {
                val hours = num("hours", 6, 48)
                val samples = HealthData.heartRates(context, now.minus(Duration.ofHours(hours.toLong())))
                if (samples.isEmpty()) {
                    "No heart rate data in the last $hours hours."
                } else {
                    // 每 30 分钟一段，给平均／最高
                    samples.groupBy { it.first.epochSecond / 1800 }.entries.sortedBy { it.key }.joinToString("\n") { (_, list) ->
                        val t = list.first().first.atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)
                        "$t avg ${list.map { it.second }.average().toInt()} max ${list.maxOf { it.second }}"
                    }
                }
            }

            "exercise" -> HealthData.exercises(context, now.minus(Duration.ofDays(num("days", 7, 30).toLong()))).joinToString("\n").ifEmpty { "No workouts." }
            else -> HealthData.today(context)
        }
        tJson("data" to result)
    }
)
