package me.rerere.rikkahub.hoshio.music

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.hoshio.together.JsonListStore
import me.rerere.rikkahub.hoshio.together.togetherJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.uuid.Uuid

private fun newId() = Uuid.random().toString().take(8)

/** 她在 YouTube／YT Music 等 App 播過的歌（手機自動記） */
@Serializable
data class ListenEntry(val at: Long = System.currentTimeMillis(), val title: String, val artist: String = "", val app: String = "")

/** 一首歌的批註：誰寫的、寫了什麼；line 有值＝收藏的那句歌詞 */
@Serializable
data class SongNote(
    val id: String = newId(),
    val title: String,
    val artist: String = "",
    val by: String,
    val text: String = "",
    val line: String? = null,
    val at: Long = System.currentTimeMillis(),
)

/** 我們的歌單 */
@Serializable
data class PlaylistSong(
    val id: String = newId(),
    val title: String,
    val artist: String = "",
    val by: String,
    val note: String = "",
    val at: Long = System.currentTimeMillis(),
)

object ListenHistory : JsonListStore<ListenEntry>("hoshio_listen_history", ListenEntry.serializer(), limit = 300) {
    /** 換歌時記一筆；同一首 10 分鐘內不重複 */
    fun log(context: Context, title: String, artist: String, app: String) {
        if (title.isBlank()) return
        update(context) { list ->
            val last = list.firstOrNull()
            if (last != null && last.title == title && System.currentTimeMillis() - last.at < 10 * 60_000) list
            else listOf(ListenEntry(title = title, artist = artist, app = app)) + list
        }
    }
}

object SongNotes : JsonListStore<SongNote>("hoshio_song_notes", SongNote.serializer(), limit = 1000) {
    fun forSong(context: Context, title: String) = load(context).filter { MusicText.sameSong(it.title, title) }
}

object OurPlaylist : JsonListStore<PlaylistSong>("hoshio_our_playlist", PlaylistSong.serializer(), limit = 500)

/** YouTube 的標題常帶「(Official Video)」「- Topic」之類，整理成比較乾淨的歌名／歌手 */
object MusicText {
    private val noise = Regex(
        "[\\(\\[（【](?:[^)\\]）】]*?(?:official|mv|m/v|music video|lyric|lyrics|audio|video|hd|4k|live|官方|歌詞|動態歌詞|完整版|中字|字幕)[^)\\]）】]*)[\\)\\]）】]",
        RegexOption.IGNORE_CASE,
    )

    fun cleanTitle(title: String) = title.replace(noise, "").replace(Regex("\\s{2,}"), " ").trim()

    fun cleanArtist(artist: String) = artist.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "").trim()

    /** 標題「歌手 - 歌名」的格式：拆開 */
    fun split(title: String, artist: String): Pair<String, String> {
        val t = cleanTitle(title)
        val a = cleanArtist(artist)
        val dash = Regex("^(.+?)\\s+[-–—]\\s+(.+)$").find(t)
        return if (dash != null && (a.isBlank() || dash.groupValues[1].contains(a, true) || a.contains(dash.groupValues[1], true))) {
            dash.groupValues[2].trim() to dash.groupValues[1].trim()
        } else t to a
    }

    fun sameSong(a: String, b: String) = cleanTitle(a).equals(cleanTitle(b), true) ||
        split(a, "").first.equals(split(b, "").first, true)
}

data class NowPlaying(val title: String, val artist: String, val app: String, val playing: Boolean, val positionMs: Long, val durationMs: Long)

fun MediaController.toNowPlaying(appLabel: String): NowPlaying? {
    val meta = metadata ?: return null
    val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
    if (title.isBlank()) return null
    val artist = (meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
    val state = playbackState
    // position 是上次更新時的值，播放中要補上經過的時間
    val pos = state?.let {
        if (it.state == PlaybackState.STATE_PLAYING && it.lastPositionUpdateTime > 0)
            it.position + ((android.os.SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
        else it.position
    } ?: 0L
    return NowPlaying(title, artist, appLabel, state?.state == PlaybackState.STATE_PLAYING, pos.coerceAtLeast(0), meta.getLong(MediaMetadata.METADATA_KEY_DURATION))
}

/** 歌詞：LRCLIB（免費、不用金鑰、有時間軸） */
object Lyrics {
    data class Line(val ms: Long, val text: String)
    data class Result(val title: String, val artist: String, val synced: List<Line>, val plain: String)

    private val cache = mutableMapOf<String, Result?>()

    suspend fun find(title: String, artist: String, durationSec: Long = 0): Result? = withContext(Dispatchers.IO) {
        val (t, a) = MusicText.split(title, artist)
        val key = "$t|$a"
        cache[key]?.let { return@withContext it }
        val client = runCatching { getKoin().get<OkHttpClient>() }.getOrElse { OkHttpClient() }
        fun search(track: String, art: String?): JsonArray? = runCatching {
            val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", track)
                .apply { if (!art.isNullOrBlank()) addQueryParameter("artist_name", art) }
                .build()
            client.newCall(Request.Builder().url(url).header("User-Agent", "Hoshio companion app").build()).execute().use { r ->
                if (!r.isSuccessful) null else togetherJson.parseToJsonElement(r.body.string()) as? JsonArray
            }
        }.getOrNull()
        val results = (search(t, a.ifBlank { null })?.takeIf { it.isNotEmpty() } ?: search(t, null)).orEmpty()
            .mapNotNull { it as? JsonObject }
        val best = results
            .sortedWith(compareByDescending<JsonObject> { !(it["syncedLyrics"]?.jsonPrimitive?.contentOrNull).isNullOrBlank() }
                .thenBy { o -> if (durationSec > 0) kotlin.math.abs((o["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) - durationSec) else 0.0 })
            .firstOrNull()
        val result = best?.let { o ->
            val synced = parseLrc(o["syncedLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val plain = o["plainLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (synced.isEmpty() && plain.isBlank()) null
            else Result(o["trackName"]?.jsonPrimitive?.contentOrNull ?: t, o["artistName"]?.jsonPrimitive?.contentOrNull ?: a, synced, plain)
        }
        cache[key] = result
        result
    }

    private fun parseLrc(lrc: String): List<Line> = lrc.lines().flatMap { raw ->
        val stamps = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)]").findAll(raw).toList()
        if (stamps.isEmpty()) return@flatMap emptyList()
        val text = raw.substring(stamps.last().range.last + 1).trim()
        stamps.map { m -> Line((m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong()), text) }
    }.filter { it.text.isNotBlank() }.sortedBy { it.ms }

    fun fmt(ms: Long) = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
}
