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
)
