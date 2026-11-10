package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.HOSHIO_ASSISTANT_ID
import me.rerere.rikkahub.hoshio.SelfCheck
import me.rerere.rikkahub.hoshio.anniversary.AnniversaryStore
import me.rerere.rikkahub.hoshio.care.CarePlace
import me.rerere.rikkahub.hoshio.care.CarePrefs
import me.rerere.rikkahub.hoshio.care.CareWorker
import kotlin.uuid.Uuid

/** 他可以帮她调的一项设定 */
private class SettingKey(
    val key: String,
    val description: String,
    val get: (CarePrefs, Context) -> String,
    val set: (CarePrefs, Context, String) -> String?, // 回传错误讯息，null 表示成功
)

private fun bool(key: String, description: String, get: (CarePrefs) -> Boolean, set: (CarePrefs, Boolean) -> Unit) = SettingKey(
    key, "$description (true/false)",
    { p, _ -> get(p).toString() },
    { p, _, v ->
        when (v.lowercase()) {
            "true", "on", "1", "開", "开" -> { set(p, true); null }
            "false", "off", "0", "關", "关" -> { set(p, false); null }
            else -> "value must be true or false"
        }
    },
)

private fun int(key: String, description: String, range: IntRange, get: (CarePrefs) -> Int, set: (CarePrefs, Int) -> Unit) = SettingKey(
    key, "$description (${range.first}-${range.last})",
    { p, _ -> get(p).toString() },
    { p, _, v ->
        val n = v.trim().toIntOrNull()
        if (n == null || n !in range) "value must be a number in ${range.first}-${range.last}" else { set(p, n); null }
    },
)

private val SETTINGS = listOf(
    bool("low_battery", "remind her to charge when battery is low", { it.lowBatteryEnabled }, { p, v -> p.lowBatteryEnabled = v }),
    int("low_battery_percent", "low battery threshold %", 5..50, { it.lowBatteryPercent }, { p, v -> p.lowBatteryPercent = v }),
    bool("late_night", "tell her to rest when using the phone late at night", { it.nightUsageEnabled }, { p, v -> p.nightUsageEnabled = v }),
    int("night_start_hour", "late night starts at hour", 0..23, { it.nightStartHour }, { p, v -> p.nightStartHour = v }),
    int("night_end_hour", "late night ends at hour", 0..23, { it.nightEndHour }, { p, v -> p.nightEndHour = v }),
    int("night_usage_minutes", "minutes of late-night screen time before reminding", 5..180, { it.nightUsageMinutes }, { p, v -> p.nightUsageMinutes = v }),
    bool("wake_up", "good morning greeting when she wakes up (needs usage access)", { it.wakeUpEnabled }, { p, v -> p.wakeUpEnabled = v }),
    int("wake_min_sleep_hours", "screen-off hours that count as sleep", 2..10, { it.wakeMinSleepHours }, { p, v -> p.wakeMinSleepHours = v }),
    int("wake_start_hour", "earliest wake-up hour", 0..23, { it.wakeStartHour }, { p, v -> p.wakeStartHour = v }),
    int("wake_end_hour", "latest wake-up hour", 1..24, { it.wakeEndHour }, { p, v -> p.wakeEndHour = v }),
    bool("bedtime", "goodnight greeting at bedtime", { it.bedtimeEnabled }, { p, v -> p.bedtimeEnabled = v }),
    int("bedtime_hour", "bedtime hour", 0..23, { it.bedtimeHour }, { p, v -> p.bedtimeHour = v }),
    bool("places", "arrive/leave greetings for saved places (needs background location)", { it.placesEnabled }, { p, v -> p.placesEnabled = v }),
    bool("screen_peek", "peek at her screen now and then (needs accessibility service)", { it.screenObservationEnabled }, { p, v -> p.screenObservationEnabled = v }),
    SettingKey(
        "screen_peek_mode", "ignored = only when she has not replied for a while, interval = every N minutes",
        { p, _ -> p.screenObservationMode },
        { p, _, v -> if (v in listOf(CarePrefs.SCREEN_MODE_IGNORED, CarePrefs.SCREEN_MODE_INTERVAL)) { p.screenObservationMode = v; null } else "value must be ignored or interval" },
    ),
    int("screen_peek_minutes", "screen peek interval minutes", 15..240, { it.screenObservationIntervalMinutes }, { p, v -> p.screenObservationIntervalMinutes = v }),
    bool("free_time", "your free time: explore on your own when she has been away", { it.freeTimeEnabled }, { p, v -> p.freeTimeEnabled = v }),
    int("free_time_idle_minutes", "minutes she is away before free time starts", 30..720, { it.freeTimeIdleMinutes }, { p, v -> p.freeTimeIdleMinutes = v }),
    bool("diary", "you write a diary every night", { it.diaryEnabled }, { p, v -> p.diaryEnabled = v }),
    int("diary_write_hour", "hour after which you write the diary", 18..23, { it.diaryWriteHour }, { p, v -> p.diaryWriteHour = v }),
    bool("always_on", "always-on mode: check every 2 minutes with a persistent notification (more battery)", { it.alwaysOnEnabled }, { p, v -> p.alwaysOnEnabled = v }),
    SettingKey(
        "anniversary_reminder", "remind you on saved anniversaries (true/false)",
        { _, c -> AnniversaryStore.remindEnabled(c).toString() },
        { _, c, v -> AnniversaryStore.setRemindEnabled(c, v.lowercase() in listOf("true", "on", "1")); null },
    ),
)

/**
 * 星汐「AI 协助设定」：他可以看、帮她调主动关心的设定，打开要授权的系统设定页，
 * 以及把她现在的位置存成地点。只在她要求或同意时使用。
 */
internal fun buildSettingsHelperTool(context: Context): Tool = Tool(
    name = "hoshio_settings",
    description = """
        Help her set up this app. action=get lists the proactive-care settings and their values; action=set changes one (key + value) -
        only when she asks or clearly agrees, and tell her what you changed. action=open_permission opens the Android settings page for a
        permission check item (use hoshio_self_check first to see which are missing; item = the check title). action=save_place saves her
        current location as a place (name, e.g. 家 / 公司) for arrive/leave greetings. Keys: 
    """.trimIndent().replace("\n", " ") + SETTINGS.joinToString("; ") { "${it.key}: ${it.description}" },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", tEnum("What to do", "get", "set", "open_permission", "save_place"))
                put("key", tStr("set: which setting"))
                put("value", tStr("set: new value"))
                put("item", tStr("open_permission: the self-check item title"))
                put("name", tStr("save_place: place name"))
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val prefs = CarePrefs(context)
        when (arg("action")) {
            "set" -> {
                val setting = SETTINGS.firstOrNull { it.key == arg("key") }
                if (setting == null) {
                    tJson("error" to "Unknown key. Available: " + SETTINGS.joinToString(", ") { it.key })
                } else {
                    val before = setting.get(prefs, context)
                    val error = setting.set(prefs, context, arg("value"))
                    if (error != null) {
                        tJson("error" to error)
                    } else {
                        if (prefs.assistantId == null) prefs.assistantId = HOSHIO_ASSISTANT_ID
                        CareWorker.sync(context)
                        tJson("ok" to "${setting.key}: $before → ${setting.get(prefs, context)}", "permissions" to SelfCheck.summary(context).lines().take(12).joinToString(" | "))
                    }
                }
            }

            "open_permission" -> {
                val item = SelfCheck.run(context).firstOrNull { it.title == arg("item") || arg("item").isNotEmpty() && it.title.contains(arg("item")) }
                val intent = item?.settingsIntent
                if (intent == null) {
                    tJson("error" to "No settings page for that item. Items: " + SelfCheck.run(context).joinToString(", ") { it.title })
                } else {
                    val opened = runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                    tJson("ok" to if (opened) "Opened the settings page for ${item.title}. Tell her what to tap: ${item.fix}" else "Could not open it. Tell her: ${item.fix}")
                }
            }

            "save_place" -> {
                val name = arg("name").ifEmpty { "家" }.take(20)
                val location = context.currentLocationOrNull()
                if (location == null) {
                    tJson("error" to "Could not get her location (location permission off or no fix). Ask her to check location permission.")
                } else {
                    prefs.places = prefs.places.filterNot { it.name == name } +
                        CarePlace(id = Uuid.random().toString(), name = name, latitude = location.latitude, longitude = location.longitude)
                    if (prefs.assistantId == null) prefs.assistantId = HOSHIO_ASSISTANT_ID
                    CareWorker.sync(context)
                    tJson(
                        "ok" to "Saved $name (accuracy about ${location.accuracy.toInt()} m).",
                        "places_enabled" to prefs.placesEnabled,
                        "hint" to if (!prefs.placesEnabled) "Arrive/leave greetings are off; ask her if you should turn on key 'places'." else null,
                    )
                }
            }

            else -> tJson(
                "settings" to SETTINGS.joinToString("; ") { "${it.key}=${it.get(prefs, context)}" },
                "places" to prefs.places.joinToString(", ") { it.name }.ifEmpty { "none" },
            )
        }
    }
)
