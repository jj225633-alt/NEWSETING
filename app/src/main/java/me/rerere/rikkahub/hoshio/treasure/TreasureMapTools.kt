// Based on hidden-letters-map by Saekisui - https://github.com/Saekisui/hidden-letters-map
// AI 端工具：藏信／發成就／看地圖／收回。地名查座標用 OpenStreetMap Nominatim（免金鑰，一次查一個）。
package me.rerere.rikkahub.hoshio.treasure

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

object TreasureMapTools {
    private fun ok(extra: JSONObject.() -> Unit) = JSONObject().put("ok", true).apply(extra).toString()
    private fun fail(code: String, message: String) = JSONObject().put("ok", false).put("error", code).put("message", message).toString()

    fun hide(context: Context, args: JSONObject): String {
        val place = args.optString("place").trim()
        val hint = args.optString("hint").trim()
        if (place.isEmpty() || hint.isEmpty()) return fail("missing_place_or_hint", "place 和 hint 都要填。")
        val lat = args.optDouble("lat", Double.NaN)
        val lon = args.optDouble("lon", Double.NaN)
        val coords = if (lat.isFinite() && lon.isFinite()) lat to lon else {
            val query = args.optString("address").trim().ifEmpty { place }
            geocode(query) ?: return fail("geocode_failed",
                "地圖查不到「$query」。請改寫成更完整的地址（城市＋路名或地標全名），或直接提供 lat / lon。")
        }
        val badge = TreasureLogic.normalizeBadge(args.optString("badge_name"), args.optString("badge_emoji"), args.optString("badge_note"))
        val letter = runCatching {
            TreasureLogic.createLetter(place, hint, args.optString("letter"), badge, coords.first, coords.second,
                args.optInt("radius_m", 200), args.optInt("visits_needed", 1), args.optString("from"),
                System.currentTimeMillis(), Random.Default)
        }.getOrElse { return fail("invalid_letter", it.message ?: "內容不完整") }
        TreasureMapStore.update(context) { it.copy(letters = it.letters + letter) }
        return ok {
            put("id", letter.id)
            put("message", "已藏好。對方的地圖上只會看到半徑約 ${letter.area.r} 公尺的模糊圈和謎語；走進 ${letter.radiusM} 公尺內" +
                (if (letter.visitsNeeded > 1) "、來過 ${letter.visitsNeeded} 天" else "") + "才能拿到。")
            put("geocoded", !(lat.isFinite() && lon.isFinite()))
        }
    }

    fun award(context: Context, args: JSONObject): String {
        val badge = TreasureLogic.normalizeBadge(args.optString("name"), args.optString("emoji"), args.optString("note"))
            ?: return fail("missing_name", "成就要有名字（20 字內）。")
        val now = System.currentTimeMillis()
        val awarded = AwardedBadge("badge_${now.toString(36)}${Random.nextInt(1_000_000).toString(36)}", badge, args.optString("from").take(32), now)
        TreasureMapStore.update(context) { it.copy(badges = it.badges + awarded) }
        return ok { put("id", awarded.id); put("message", "成就「${badge.name}」已經掛上對方的成就牆。") }
    }

    fun overview(context: Context): String {
        val state = TreasureMapStore.load(context)
        return ok {
            put("letters", JSONArray().apply { state.letters.forEach { put(TreasureLogic.aiLetter(it)) } })
            put("badges", JSONArray().apply { state.badges.forEach { b ->
                put(TreasureLogic.badgeJson(b.badge).put("id", b.id).put("awarded_at", java.time.Instant.ofEpochMilli(b.awardedAt).toString()))
            } })
        }
    }

    fun remove(context: Context, args: JSONObject): String {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return fail("missing_id", "請提供 treasure_map 裡的 id。")
        var result = "not_found"
        TreasureMapStore.update(context) { s ->
            val letter = s.letters.firstOrNull { it.id == id }
            when {
                letter != null && letter.status != "sealed" -> { result = "already_found"; s }
                letter != null -> { result = "removed"; s.copy(letters = s.letters - letter) }
                s.badges.any { it.id == id } -> { result = "removed"; s.copy(badges = s.badges.filterNot { it.id == id }) }
                else -> s
            }
        }
        return when (result) {
            "removed" -> ok { put("message", "已收回。") }
            "already_found" -> fail("already_found", "這個已經被找到了，是對方的了，不能收回。")
            else -> fail("not_found", "找不到這個 id。")
        }
    }

    private fun geocode(query: String): Pair<Double, Double>? = runCatching {
        val url = URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=zh-TW,zh,en&q=" +
            URLEncoder.encode(query, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", "Hoshio-TreasureMap/1.0 (personal companion app)")
        }
        try {
            if (conn.responseCode != 200) return@runCatching null
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            if (arr.length() == 0) null else arr.getJSONObject(0).let { it.getString("lat").toDouble() to it.getString("lon").toDouble() }
        } finally { conn.disconnect() }
    }.getOrNull()
}
