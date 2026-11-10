// Based on hidden-letters-map by Saekisui - https://github.com/Saekisui/hidden-letters-map
// (AGPL-3.0 with attribution term; see assets/orbis-treasure/NOTICE). Ported to Kotlin for 星汐.
//
// 藏寶地圖：AI 把信或成就藏在真實的地方，你的地圖上只看得到一片模糊的圈和一句謎語；
// 人走進信的半徑裡才拿得到（背景安全位置播報上報的位置，或在頁面按「我就在附近」當場定位）。
package me.rerere.rikkahub.hoshio.treasure

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class TreasureBadge(val name: String, val emoji: String, val note: String)

data class TreasureArea(val lat: Double, val lon: Double, val r: Int)

data class TreasureLetter(
    val id: String,
    val place: String,
    val hint: String,
    val body: String,
    val badge: TreasureBadge?,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val visitsNeeded: Int,
    val visitDays: List<String>,
    val area: TreasureArea,
    val status: String, // sealed → found → opened
    val author: String,
    val createdAt: Long,
    val foundAt: Long?,
    val openedAt: Long?,
)

data class AwardedBadge(val id: String, val badge: TreasureBadge, val author: String, val awardedAt: Long)

data class TreasureState(val letters: List<TreasureLetter> = emptyList(), val badges: List<AwardedBadge> = emptyList())

object TreasureLogic {
    private const val EARTH_R = 6_371_000.0
    private fun rad(d: Double) = d * PI / 180

    fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = rad(bLat - aLat)
        val dLon = rad(bLon - aLon)
        val h = sin(dLat / 2).let { it * it } + cos(rad(aLat)) * cos(rad(bLat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_R * asin(sqrt(h))
    }

    /** 地圖上露出的圈：半徑是信的 3 倍（至少 300m），圓心隨機偏開最多半個圈，信的觸發範圍一定在圈內。 */
    fun hintArea(lat: Double, lon: Double, radiusM: Int, random: Random): TreasureArea {
        val r = max(300, radiusM * 3).toDouble()
        val d = random.nextDouble() * r / 2
        val theta = random.nextDouble() * 2 * PI
        return TreasureArea(
            lat + (d * cos(theta) / EARTH_R) * (180 / PI),
            lon + (d * sin(theta) / (EARTH_R * cos(rad(lat)))) * (180 / PI),
            r.roundToInt(),
        )
    }

    fun normalizeBadge(name: String?, emoji: String?, note: String?): TreasureBadge? {
        val n = name?.trim()?.take(20).orEmpty()
        if (n.isEmpty()) return null
        return TreasureBadge(n, emoji?.trim()?.take(8)?.ifEmpty { null } ?: "🏅", note?.trim()?.take(120).orEmpty())
    }

    fun createLetter(
        place: String, hint: String, body: String, badge: TreasureBadge?, lat: Double, lon: Double,
        radiusM: Int?, visitsNeeded: Int?, author: String, now: Long, random: Random,
    ): TreasureLetter {
        require(lat.isFinite() && lon.isFinite() && kotlin.math.abs(lat) <= 90 && kotlin.math.abs(lon) <= 180) { "座標不對" }
        val p = place.trim().take(40)
        val h = hint.trim().take(200)
        require(p.isNotEmpty() && h.isNotEmpty()) { "place 和 hint 都要有" }
        val b = body.trim().take(8000)
        require(b.isNotEmpty() || badge != null) { "信和成就至少藏一樣" }
        val r = min(20_000, max(50, radiusM ?: 200))
        return TreasureLetter(
            id = "letter_${now.toString(36)}${random.nextInt(1_000_000).toString(36)}",
            place = p, hint = h, body = b, badge = badge, lat = lat, lon = lon, radiusM = r,
            visitsNeeded = min(100, max(1, visitsNeeded ?: 1)), visitDays = emptyList(),
            area = hintArea(lat, lon, r, random), status = "sealed", author = author.take(32),
            createdAt = now, foundAt = null, openedAt = null,
        )
    }

    /** 還封著、離信不超過半徑的信（定位誤差最多讓 50m）。 */
    fun inReach(letters: List<TreasureLetter>, lat: Double, lon: Double, accuracy: Float): List<TreasureLetter> {
        val slack = min(accuracy.toDouble(), 50.0)
        return letters.filter { it.status == "sealed" && distanceM(it.lat, it.lon, lat, lon) <= it.radiusM + slack }
    }

    data class Visit(val letter: TreasureLetter, val event: String) // visit | found

    /** 這一刻在 (lat, lon)：每封在範圍內的信今天記一次到訪，攢夠天數就算找到。 */
    fun visitAt(state: TreasureState, lat: Double, lon: Double, accuracy: Float, now: Long, zone: ZoneId): Pair<TreasureState, List<Visit>> {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val reach = inReach(state.letters, lat, lon, accuracy).map { it.id }.toSet()
        val events = mutableListOf<Visit>()
        val letters = state.letters.map { l ->
            if (l.id !in reach) return@map l
            val fresh = day !in l.visitDays
            val days = if (fresh) l.visitDays + day else l.visitDays
            when {
                days.size >= l.visitsNeeded -> l.copy(visitDays = days, status = "found", foundAt = now).also { events += Visit(it, "found") }
                fresh -> l.copy(visitDays = days).also { events += Visit(it, "visit") }
                else -> l
            }
        }
        return state.copy(letters = letters) to events
    }

    /** 給人類地圖看的樣子：封著的只有模糊圈、謎語、藏了什麼；找到才有地名和位置；拆開才有內容。 */
    fun publicLetter(l: TreasureLetter): JSONObject = JSONObject().apply {
        put("id", l.id); put("status", l.status); put("hint", l.hint); put("author", l.author)
        put("area", JSONObject().put("lat", l.area.lat).put("lon", l.area.lon).put("r", l.area.r))
        put("contents", JSONArray().apply { if (l.body.isNotEmpty()) put("letter"); if (l.badge != null) put("badge") })
        put("createdAt", l.createdAt)
        if (l.visitsNeeded > 1) { put("visits", l.visitDays.size); put("visitsNeeded", l.visitsNeeded) }
        if (l.status == "sealed") return@apply
        put("place", l.place); put("lat", l.lat); put("lon", l.lon); put("foundAt", l.foundAt)
        if (l.status == "found") return@apply
        put("body", l.body); put("openedAt", l.openedAt)
        l.badge?.let { put("badge", badgeJson(it)) }
    }

    /** 給 AI 看的完整樣子（含位置與狀態），讓它知道哪些已經被找到。 */
    fun aiLetter(l: TreasureLetter): JSONObject = JSONObject().apply {
        put("id", l.id); put("place", l.place); put("hint", l.hint); put("status", l.status)
        put("radius_m", l.radiusM); put("visits", l.visitDays.size); put("visits_needed", l.visitsNeeded)
        put("has_letter", l.body.isNotEmpty()); l.badge?.let { put("badge", badgeJson(it)) }
        put("created_at", Instant.ofEpochMilli(l.createdAt).toString())
        l.foundAt?.let { put("found_at", Instant.ofEpochMilli(it).toString()) }
        l.openedAt?.let { put("opened_at", Instant.ofEpochMilli(it).toString()) }
    }

    fun badgeJson(b: TreasureBadge): JSONObject = JSONObject().put("name", b.name).put("emoji", b.emoji).put("note", b.note)

    // ---- 儲存格式 ----
    fun encode(state: TreasureState): String = JSONObject().apply {
        put("version", 1)
        put("letters", JSONArray().apply { state.letters.forEach { l ->
            put(JSONObject().apply {
                put("id", l.id); put("place", l.place); put("hint", l.hint); put("body", l.body)
                l.badge?.let { put("badge", badgeJson(it)) }
                put("lat", l.lat); put("lon", l.lon); put("radiusM", l.radiusM); put("visitsNeeded", l.visitsNeeded)
                put("visitDays", JSONArray(l.visitDays))
                put("area", JSONObject().put("lat", l.area.lat).put("lon", l.area.lon).put("r", l.area.r))
                put("status", l.status); put("author", l.author); put("createdAt", l.createdAt)
                l.foundAt?.let { put("foundAt", it) }; l.openedAt?.let { put("openedAt", it) }
            })
        } })
        put("badges", JSONArray().apply { state.badges.forEach { b ->
            put(JSONObject().put("id", b.id).put("badge", badgeJson(b.badge)).put("author", b.author).put("awardedAt", b.awardedAt))
        } })
    }.toString()

    private fun badgeFrom(o: JSONObject?): TreasureBadge? =
        o?.let { normalizeBadge(it.optString("name"), it.optString("emoji"), it.optString("note")) }

    fun decode(text: String): TreasureState {
        val root = JSONObject(text)
        val letters = root.optJSONArray("letters") ?: JSONArray()
        val badges = root.optJSONArray("badges") ?: JSONArray()
        return TreasureState(
            (0 until letters.length()).map { i ->
                val o = letters.getJSONObject(i)
                val a = o.getJSONObject("area")
                val days = o.optJSONArray("visitDays") ?: JSONArray()
                TreasureLetter(
                    o.getString("id"), o.getString("place"), o.getString("hint"), o.optString("body"),
                    badgeFrom(o.optJSONObject("badge")), o.getDouble("lat"), o.getDouble("lon"), o.getInt("radiusM"),
                    o.optInt("visitsNeeded", 1), (0 until days.length()).map { days.getString(it) },
                    TreasureArea(a.getDouble("lat"), a.getDouble("lon"), a.getInt("r")), o.getString("status"),
                    o.optString("author"), o.getLong("createdAt"),
                    if (o.has("foundAt")) o.getLong("foundAt") else null,
                    if (o.has("openedAt")) o.getLong("openedAt") else null,
                )
            },
            (0 until badges.length()).mapNotNull { i ->
                val o = badges.getJSONObject(i)
                badgeFrom(o.optJSONObject("badge"))?.let { AwardedBadge(o.getString("id"), it, o.optString("author"), o.getLong("awardedAt")) }
            },
        )
    }
}

/** 本機儲存（App 私有資料夾，一個 JSON 檔）。所有讀寫都經過同一把鎖。 */
object TreasureMapStore {
    private const val FILE = "treasure-map.json"
    private val lock = Any()
    private val listeners = mutableSetOf<() -> Unit>()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    fun load(context: Context): TreasureState = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) TreasureState() else runCatching { TreasureLogic.decode(f.readText()) }.getOrElse { TreasureState() }
    }

    private fun save(context: Context, state: TreasureState) {
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(TreasureLogic.encode(state))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun update(context: Context, transform: (TreasureState) -> TreasureState): TreasureState {
        val next = synchronized(lock) { transform(load(context)).also { save(context, it) } }
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }
        return next
    }

    fun addListener(listener: () -> Unit) { synchronized(listeners) { listeners += listener } }
    fun removeListener(listener: () -> Unit) { synchronized(listeners) { listeners -= listener } }

    /** 在這個位置記一次到訪；有信被找到時發通知。回傳這次的事件。 */
    fun visit(context: Context, lat: Double, lon: Double, accuracy: Float, now: Long = System.currentTimeMillis()): List<TreasureLogic.Visit> {
        var events: List<TreasureLogic.Visit> = emptyList()
        // 沒有封著的信時不寫檔，背景定位才不會一直動到儲存。
        if (load(context).letters.none { it.status == "sealed" }) return emptyList()
        update(context) { state ->
            val (next, ev) = TreasureLogic.visitAt(state, lat, lon, accuracy, now, ZoneId.systemDefault())
            events = ev
            if (ev.isEmpty()) state else next
        }
        events.filter { it.event == "found" }.forEach { TreasureNotifications.found(context, it.letter) }
        return events
    }

    fun open(context: Context, id: String, now: Long = System.currentTimeMillis()): TreasureState = update(context) { s ->
        s.copy(letters = s.letters.map { if (it.id == id && it.status == "found") it.copy(status = "opened", openedAt = now) else it })
    }
}

internal object TreasureNotifications {
    private const val CHANNEL = "hoshio_treasure"

    fun found(context: Context, letter: TreasureLetter) = runCatching {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "藏寶地圖",
                android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        val pending = launch?.let {
            android.app.PendingIntent.getActivity(app, letter.id.hashCode(), it,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val what = listOfNotNull(if (letter.body.isNotEmpty()) "一封信" else null, letter.badge?.let { "成就「${it.name}」" }).joinToString("和")
        val notification = androidx.core.app.NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("你找到了藏在「${letter.place}」的東西！")
            .setContentText("${letter.author.ifEmpty { "對方" }}留了$what 給你，到星汐的藏寶地圖拆開吧。")
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()
        manager.notify(letter.id.hashCode(), notification)
    }
}
