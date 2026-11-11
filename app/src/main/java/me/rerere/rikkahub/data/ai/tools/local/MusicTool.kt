package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.music.HoshioNotificationListener
import me.rerere.rikkahub.hoshio.music.toNowPlaying
import java.net.URLEncoder

private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
private const val YT_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"

private fun Context.mediaControllers(): List<MediaController>? {
    if (!HoshioNotificationListener.isEnabled(this)) return null
    val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return emptyList()
    return runCatching { manager.getActiveSessions(HoshioNotificationListener.component(this)) }.getOrDefault(emptyList())
}

private fun Context.appLabelOf(packageName: String): String = runCatching {
    @Suppress("DEPRECATION")
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)

private fun PlaybackState?.label(): String = when (this?.state) {
    PlaybackState.STATE_PLAYING -> "playing"
    PlaybackState.STATE_PAUSED -> "paused"
    PlaybackState.STATE_BUFFERING -> "buffering"
    PlaybackState.STATE_STOPPED -> "stopped"
    else -> "unknown"
}

private const val NEED_PERMISSION =
    "Notification access for 星汐 is not enabled, so you cannot see what she is playing. " +
        "Ask her to turn on the music tool in your local tools settings and allow notification access."

/** 正在播放的优先，其次是暂停中的 */
private fun List<MediaController>.mostRelevant(): MediaController? =
    firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: firstOrNull()

/**
 * 星汐: 一起听音乐。看她在 YouTube／YT Music／Spotify 等 App 听什么、帮她控制播放、帮她在 YouTube 放歌。
 */
internal fun buildMusicTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "now_playing",
        description = """
            See what she is listening to right now in any music or video app (YouTube, YouTube Music, Spotify...):
            app, title, artist, album, whether it is playing, and the position. You cannot hear the audio, only this information.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val controllers = context.mediaControllers()
            val payload = if (controllers == null) {
                buildJsonObject { put("error", NEED_PERMISSION) }.toString()
            } else if (controllers.isEmpty()) {
                buildJsonObject { put("result", "nothing is playing") }.toString()
            } else {
                buildJsonArray {
                    controllers.forEach { c ->
                        val meta = c.metadata
                        add(buildJsonObject {
                            put("app", context.appLabelOf(c.packageName))
                            put("state", c.playbackState.label())
                            meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { put("title", it) }
                            (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
                                ?.let { put("artist", it) }
                            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.let { put("album", it) }
                            val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                            if (duration > 0) put("duration_seconds", duration / 1000)
                            c.playbackState?.position?.takeIf { it > 0 }?.let { put("position_seconds", it / 1000) }
                        })
                    }
                }.toString()
            }
            listOf(UIMessagePart.Text(payload))
        }
    ),
    Tool(
        name = "music_control",
        description = "Control what she is currently playing: play, pause, next or previous. Only do this when she asks or clearly agrees.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", "play | pause | next | previous")
                    })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val action = args.jsonObject["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            val controllers = context.mediaControllers()
            val payload = when {
                controllers == null -> buildJsonObject { put("error", NEED_PERMISSION) }
                controllers.isEmpty() -> buildJsonObject { put("error", "nothing is playing") }
                else -> {
                    val c = controllers.mostRelevant()!!
                    val controls = c.transportControls
                    when (action) {
                        "play" -> controls.play()
                        "pause" -> controls.pause()
                        "next" -> controls.skipToNext()
                        "previous" -> controls.skipToPrevious()
                        else -> error("action must be play, pause, next or previous")
                    }
                    buildJsonObject { put("done", action); put("app", context.appLabelOf(c.packageName)) }
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "play_on_youtube",
        description = """
            Open a song or video for her on YouTube Music (default) or YouTube by searching for it, e.g. when you recommend a song
            or she asks you to put something on. She will see the search results and tap to play. Only works while she is using the phone.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject { put("type", "string"); put("description", "Song title and artist, or what to search") })
                    put("app", buildJsonObject { put("type", "string"); put("description", "ytmusic (default) or youtube") })
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val query = obj["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { error("query is required") }
            val wantYouTube = obj["app"]?.jsonPrimitive?.contentOrNull?.contains("youtube", ignoreCase = true) == true &&
                obj["app"]?.jsonPrimitive?.contentOrNull?.contains("music", ignoreCase = true) != true
            val q = URLEncoder.encode(query, "UTF-8")
            fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
            val (url, pkg) = if (wantYouTube || !installed(YT_MUSIC_PACKAGE)) {
                "https://www.youtube.com/results?search_query=$q" to YOUTUBE_PACKAGE
            } else {
                "https://music.youtube.com/search?q=$q" to YT_MUSIC_PACKAGE
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (installed(pkg)) intent.setPackage(pkg)
            val result = runCatching { context.startActivity(intent) }
            listOf(UIMessagePart.Text(buildJsonObject {
                if (result.isSuccess) {
                    put("opened", true)
                    put("app", if (pkg == YT_MUSIC_PACKAGE) "YouTube Music" else "YouTube")
                    put("query", query)
                } else {
                    put("error", "could not open the app right now (she may not be using the phone): ${result.exceptionOrNull()?.message}")
                }
            }.toString()))
        }
    ),

    Tool(
        name = "song_lyrics",
        description = """
            Read the lyrics of a song (from the free LRCLIB library), with timestamps when available. Leave title empty to use what she is
            playing right now - then the line playing at this moment is marked with ▶ so you can talk about that exact line.
            Not every song is in the library; if not found, say so instead of making lyrics up. Never invent or misquote lyrics.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("title", buildJsonObject { put("type", "string"); put("description", "Song title; empty = what she is playing now") })
                put("artist", buildJsonObject { put("type", "string"); put("description", "Artist (optional)") })
            })
        },
        execute = { args ->
            val obj = args.jsonObject
            var title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            var artist = obj["artist"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            var now: me.rerere.rikkahub.hoshio.music.NowPlaying? = null
            if (title.isBlank()) {
                val c = context.mediaControllers()?.mostRelevant()
                now = c?.toNowPlaying(context.appLabelOf(c.packageName))
                if (now == null) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("error", "She is not playing anything I can see; give a title.") }.toString()))
                title = now.title; artist = now.artist
            }
            val lyrics = me.rerere.rikkahub.hoshio.music.Lyrics.find(title, artist, (now?.durationMs ?: 0) / 1000)
            val text = if (lyrics == null) {
                buildJsonObject { put("error", "No lyrics found for \"$title\" in LRCLIB. Do not make lyrics up.") }.toString()
            } else buildString {
                append("${lyrics.title} - ${lyrics.artist}\n")
                if (lyrics.synced.isNotEmpty()) {
                    val pos = now?.positionMs ?: -1L
                    val current = if (pos >= 0) lyrics.synced.indexOfLast { it.ms <= pos } else -1
                    if (now != null) append("(she is at ${me.rerere.rikkahub.hoshio.music.Lyrics.fmt(pos)}${if (now.playing) ", playing" else ", paused"})\n")
                    lyrics.synced.forEachIndexed { i, l ->
                        append(if (i == current) "▶ " else "  ").append("[${me.rerere.rikkahub.hoshio.music.Lyrics.fmt(l.ms)}] ").append(l.text).append('\n')
                    }
                } else append(lyrics.plain)
            }.take(6000)
            listOf(UIMessagePart.Text(text))
        }
    ),
    Tool(
        name = "her_recent_music",
        description = "See the songs she played recently on her phone (YouTube, YT Music, Spotify...), newest first, with time. Logged automatically on the phone.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { put("limit", buildJsonObject { put("type", "integer"); put("description", "default 20") }) }) },
        execute = { args ->
            val limit = args.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20
            val fmt = java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm")
            val list = me.rerere.rikkahub.hoshio.music.ListenHistory.load(context).take(limit)
            val text = if (list.isEmpty()) {
                if (!HoshioNotificationListener.isEnabled(context)) NEED_PERMISSION else "No songs logged yet."
            } else list.joinToString("\n") {
                java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault()).format(fmt) +
                    "  ${it.title}" + (if (it.artist.isNotBlank()) " - ${it.artist}" else "") + "  (${it.app})"
            }
            listOf(UIMessagePart.Text(text))
        }
    ),
    Tool(
        name = "song_note",
        description = """
            Your shared song notebook. action=add writes your feeling about a song (text), optionally saving a favourite lyric line (line);
            leave title empty to use what she is playing now. action=read shows the notes you both wrote for a song (title) or the latest notes.
            Notes are signed and never overwrite hers.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject { put("type", "string"); put("description", "add | read") })
                    put("title", buildJsonObject { put("type", "string") })
                    put("artist", buildJsonObject { put("type", "string") })
                    put("text", buildJsonObject { put("type", "string"); put("description", "add: your note") })
                    put("line", buildJsonObject { put("type", "string"); put("description", "add: a lyric line to keep (copy it exactly from song_lyrics)") })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            var title = arg("title"); var artist = arg("artist")
            if (title.isBlank()) {
                val c = context.mediaControllers()?.mostRelevant()
                c?.toNowPlaying(context.appLabelOf(c.packageName))?.let { title = it.title; artist = it.artist }
            }
            val (t, a) = me.rerere.rikkahub.hoshio.music.MusicText.split(title, artist)
            val text = when (arg("action")) {
                "add" -> if (t.isBlank() || (arg("text").isBlank() && arg("line").isBlank())) "error: need a song (title or something playing) and text or line" else {
                    me.rerere.rikkahub.hoshio.music.SongNotes.update(context) {
                        listOf(me.rerere.rikkahub.hoshio.music.SongNote(title = t, artist = a, by = "him", text = arg("text"), line = arg("line").ifBlank { null })) + it
                    }
                    "Saved to your song notebook for \"$t\"."
                }
                else -> {
                    val notes = if (t.isNotBlank()) me.rerere.rikkahub.hoshio.music.SongNotes.forSong(context, t) else me.rerere.rikkahub.hoshio.music.SongNotes.load(context).take(20)
                    if (notes.isEmpty()) "No notes yet." else notes.joinToString("\n") {
                        "[${if (it.by == "him") "you" else "her"}] ${it.title}: " + listOfNotNull(it.line?.let { l -> "「$l」" }, it.text.ifBlank { null }).joinToString(" ")
                    }
                }
            }
            listOf(UIMessagePart.Text(text))
        }
    ),
    Tool(
        name = "our_playlist",
        description = """
            Your shared playlist ("我們的歌單"). action=list shows it; action=add adds a song (title, artist, optional note why);
            action=remove removes by id (only songs you added); action=play opens one (id) for her on YouTube Music.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject { put("type", "string"); put("description", "list | add | remove | play") })
                    put("id", buildJsonObject { put("type", "string") })
                    put("title", buildJsonObject { put("type", "string") })
                    put("artist", buildJsonObject { put("type", "string") })
                    put("note", buildJsonObject { put("type", "string") })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            fun arg(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val store = me.rerere.rikkahub.hoshio.music.OurPlaylist
            val text = when (arg("action")) {
                "add" -> if (arg("title").isBlank()) "error: title is required" else {
                    val (t, a) = me.rerere.rikkahub.hoshio.music.MusicText.split(arg("title"), arg("artist"))
                    val dup = store.load(context).any { it.title.equals(t, true) }
                    if (dup) "Already in our playlist." else {
                        store.update(context) { it + me.rerere.rikkahub.hoshio.music.PlaylistSong(title = t, artist = a, by = "him", note = arg("note")) }
                        "Added \"$t\" to our playlist."
                    }
                }
                "remove" -> {
                    val song = store.load(context).firstOrNull { it.id == arg("id") }
                    when {
                        song == null -> "error: unknown id"
                        song.by != "him" -> "error: she added this one; ask her before removing it"
                        else -> { store.update(context) { l -> l.filterNot { it.id == song.id } }; "Removed." }
                    }
                }
                "play" -> {
                    val song = store.load(context).firstOrNull { it.id == arg("id") }
                    if (song == null) "error: unknown id" else {
                        val q = URLEncoder.encode("${song.title} ${song.artist}".trim(), "UTF-8")
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.packageManager.getPackageInfo(YT_MUSIC_PACKAGE, 0); intent.setPackage(YT_MUSIC_PACKAGE) }
                        if (runCatching { context.startActivity(intent) }.isSuccess) "Opened \"${song.title}\" for her." else "error: could not open (she may not be using the phone)"
                    }
                }
                else -> store.load(context).let { l ->
                    if (l.isEmpty()) "Our playlist is empty." else l.joinToString("\n") { "[${it.id}] ${it.title}" + (if (it.artist.isNotBlank()) " - ${it.artist}" else "") + " (added by ${if (it.by == "him") "you" else "her"})" + if (it.note.isNotBlank()) "：${it.note}" else "" }
                }
            }
            listOf(UIMessagePart.Text(text))
        }
    ),
)
