package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.anniversary.Anniversary
import me.rerere.rikkahub.hoshio.anniversary.AnniversaryStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 星汐: 纪念日工具。她提到生日、纪念日等重要日子时，他可以记下来；当天会被提醒。
 */
internal fun buildAnniversaryTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "anniversary_add",
        description = """
            Save an important date she told you about (her birthday, an anniversary, an exam, a trip...).
            Only save dates she actually mentioned; ask if the exact date is unclear.
            yearly=true for things that repeat every year (birthdays, anniversaries), false for one-time events.
            On that day you will be reminded so you can remember it with her.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", buildJsonObject { put("type", "string"); put("description", "e.g. 她的生日") })
                    put("date", buildJsonObject { put("type", "string"); put("description", "yyyy-MM-dd (use the original year if known)") })
                    put("yearly", buildJsonObject { put("type", "boolean"); put("description", "Repeats every year (default true)") })
                    put("note", buildJsonObject { put("type", "string"); put("description", "Optional short note") })
                },
                required = listOf("title", "date"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val item = AnniversaryStore.add(
                context,
                Anniversary(
                    title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { error("title is required") },
                    date = obj["date"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    yearly = obj["yearly"]?.jsonPrimitive?.booleanOrNull ?: true,
                    note = obj["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    addedBy = "ai",
                ),
            )
            listOf(UIMessagePart.Text(buildJsonObject { put("saved", true); put("id", item.id) }.toString()))
        }
    ),
    Tool(
        name = "anniversary_list",
        description = "List the important dates you two have saved, with how many days until each, and the day you met.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val today = LocalDate.now()
            val payload = buildJsonObject {
                AnniversaryStore.metDate(context)?.let {
                    put("met_date", it.toString())
                    put("days_since_met", ChronoUnit.DAYS.between(it, today) + 1)
                }
                put("dates", buildJsonArray {
                    AnniversaryStore.upcoming(context, today).forEach { (a, next) ->
                        add(buildJsonObject {
                            put("id", a.id)
                            put("title", a.title)
                            put("date", a.date)
                            put("yearly", a.yearly)
                            put("next", next.toString())
                            put("days_until", ChronoUnit.DAYS.between(today, next))
                            if (a.note.isNotBlank()) put("note", a.note)
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "anniversary_remove",
        description = "Remove a saved date by id, only when she asks.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { put("id", buildJsonObject { put("type", "string") }) }, required = listOf("id"))
        },
        execute = { args ->
            val removed = AnniversaryStore.remove(context, args.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty())
            listOf(UIMessagePart.Text(buildJsonObject { put("removed", removed) }.toString()))
        }
    ),
)

private val weatherJson = Json { ignoreUnknownKeys = true }

private fun httpGetJson(url: String): JsonObject {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("User-Agent", "Hoshio-Android")
    return connection.inputStream.bufferedReader().use { weatherJson.parseToJsonElement(it.readText()).jsonObject }
}

/** WMO 天气代码转成中文 */
private fun weatherText(code: Int?): String = when (code) {
    0 -> "晴朗"
    1 -> "大致晴朗"
    2 -> "局部多雲"
    3 -> "陰天"
    45, 48 -> "有霧"
    51, 53, 55 -> "毛毛雨"
    56, 57 -> "凍毛毛雨"
    61 -> "小雨"
    63 -> "中雨"
    65 -> "大雨"
    66, 67 -> "凍雨"
    71, 73, 75, 77 -> "下雪"
    80, 81 -> "陣雨"
    82 -> "強烈陣雨"
    85, 86 -> "陣雪"
    95 -> "雷雨"
    96, 99 -> "雷雨伴隨冰雹"
    else -> "未知"
}

/**
 * 星汐: 天气工具（Open-Meteo，免金钥）。没给地点时用手机目前位置。
 */
internal fun buildWeatherTool(context: Context): Tool = Tool(
    name = "get_weather",
    description = """
        Get the current weather and today's / tomorrow's forecast. Give a city name in "location",
        or leave it empty to use the phone's current location (needs location permission).
        Use it when weather matters: greetings in the morning, plans to go out, she asks about it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("location", buildJsonObject { put("type", "string"); put("description", "City name, e.g. Taipei; empty = current location") })
        })
    },
    execute = { args ->
        val locationName = args.jsonObject["location"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val (lat, lon, place) = if (locationName.isNotBlank()) {
                    val q = URLEncoder.encode(locationName, "UTF-8")
                    val geo = httpGetJson("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=zh&format=json")
                    val first = geo["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("找不到地點：$locationName")
                    Triple(
                        first["latitude"]!!.jsonPrimitive.doubleOrNull!!,
                        first["longitude"]!!.jsonPrimitive.doubleOrNull!!,
                        first["name"]?.jsonPrimitive?.contentOrNull ?: locationName,
                    )
                } else {
                    val loc = context.currentLocationOrNull() ?: error("拿不到目前位置，請給城市名稱")
                    Triple(loc.latitude, loc.longitude, "目前位置")
                }
                val data = httpGetJson(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,apparent_temperature,weather_code,relative_humidity_2m,wind_speed_10m" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                        "&timezone=auto&forecast_days=2"
                )
                val current = data["current"]?.jsonObject
                val daily = data["daily"]?.jsonObject
                fun dailyAt(key: String, i: Int) = daily?.get(key)?.jsonArray?.getOrNull(i)?.jsonPrimitive
                buildJsonObject {
                    put("place", place)
                    current?.let {
                        put("now", buildJsonObject {
                            put("weather", weatherText(it["weather_code"]?.jsonPrimitive?.intOrNull))
                            it["temperature_2m"]?.jsonPrimitive?.doubleOrNull?.let { t -> put("temperature_c", t) }
                            it["apparent_temperature"]?.jsonPrimitive?.doubleOrNull?.let { t -> put("feels_like_c", t) }
                            it["relative_humidity_2m"]?.jsonPrimitive?.intOrNull?.let { h -> put("humidity_percent", h) }
                            it["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull?.let { w -> put("wind_kmh", w) }
                        })
                    }
                    listOf("today" to 0, "tomorrow" to 1).forEach { (label, i) ->
                        put(label, buildJsonObject {
                            put("weather", weatherText(dailyAt("weather_code", i)?.intOrNull))
                            dailyAt("temperature_2m_max", i)?.doubleOrNull?.let { put("max_c", it) }
                            dailyAt("temperature_2m_min", i)?.doubleOrNull?.let { put("min_c", it) }
                            dailyAt("precipitation_probability_max", i)?.intOrNull?.let { put("rain_chance_percent", it) }
                        })
                    }
                }
            }.getOrElse { buildJsonObject { put("error", it.message ?: "weather lookup failed") } }
        }
        listOf(UIMessagePart.Text(result.toString()))
    }
)
