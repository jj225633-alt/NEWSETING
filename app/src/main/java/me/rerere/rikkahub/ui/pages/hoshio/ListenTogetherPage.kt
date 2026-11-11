package me.rerere.rikkahub.ui.pages.hoshio

import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.hoshio.music.HoshioNotificationListener
import me.rerere.rikkahub.hoshio.music.ListenHistory
import me.rerere.rikkahub.hoshio.music.Lyrics
import me.rerere.rikkahub.hoshio.music.MusicText
import me.rerere.rikkahub.hoshio.music.NowPlaying
import me.rerere.rikkahub.hoshio.music.OurPlaylist
import me.rerere.rikkahub.hoshio.music.PlaylistSong
import me.rerere.rikkahub.hoshio.music.SongNote
import me.rerere.rikkahub.hoshio.music.SongNotes
import me.rerere.rikkahub.hoshio.music.toNowPlaying
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LISTEN_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm")

private fun currentSong(context: Context): NowPlaying? = runCatching {
    if (!HoshioNotificationListener.isEnabled(context)) return null
    val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    val list = manager.getActiveSessions(HoshioNotificationListener.component(context))
    val c = list.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING } ?: list.firstOrNull() ?: return null
    @Suppress("DEPRECATION")
    val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString() }.getOrDefault(c.packageName)
    c.toNowPlaying(label)
}.getOrNull()

private fun openOnYtMusic(context: Context, title: String, artist: String) {
    val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.packageManager.getPackageInfo("com.google.android.apps.youtube.music", 0); intent.setPackage("com.google.android.apps.youtube.music") }
    runCatching { context.startActivity(intent) }
}

/**
 * 星汐「一起聽」：正在播的歌（含當下那句歌詞）、我們的歌單、最近聽過、歌曲批註本。
 * 音樂本身在 YouTube／YT Music 播，這裡只看得到歌名和進度。
 */
@Composable
fun ListenTogetherPage() {
    val context = LocalContext.current
    val callHim = rememberCallHim()
    val playlist by OurPlaylist.items.collectAsState()
    val history by ListenHistory.items.collectAsState()
    val notes by SongNotes.items.collectAsState()
    var now by remember { mutableStateOf<NowPlaying?>(null) }
    var lyrics by remember { mutableStateOf<Lyrics.Result?>(null) }
    var lyricsFor by remember { mutableStateOf("") }
    var writing by remember { mutableStateOf<Pair<String, String?>?>(null) } // 歌名 to 歌詞句
    val granted = remember { HoshioNotificationListener.isEnabled(context) }

    LaunchedEffect(Unit) {
        OurPlaylist.load(context); ListenHistory.load(context); SongNotes.load(context)
        while (true) {
            now = currentSong(context)
            delay(1000)
        }
    }
    LaunchedEffect(now?.title) {
        val n = now ?: return@LaunchedEffect
        if (n.title != lyricsFor) {
            lyricsFor = n.title
            lyrics = null
            lyrics = Lyrics.find(n.title, n.artist, n.durationMs / 1000)
        }
    }

    PlayScaffold("一起聽") { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val n = now
                        if (!granted) {
                            Text("要先打開「通知存取」，他才看得到你在聽什麼（助手設定 → 本機工具 → 音樂）。", style = MaterialTheme.typography.bodyMedium)
                        } else if (n == null) {
                            Text("現在沒有在播歌。打開 YouTube 或 YT Music 放一首吧～", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            val (t, a) = MusicText.split(n.title, n.artist)
                            Text((if (n.playing) "🎧 正在聽" else "⏸ 暫停中") + "・${n.app}", style = MaterialTheme.typography.labelMedium)
                            Text(t, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            if (a.isNotBlank()) Text(a, style = MaterialTheme.typography.bodyMedium)
                            val line = lyrics?.synced?.lastOrNull { it.ms <= n.positionMs }?.text
                            if (line != null) Text("「$line」", style = MaterialTheme.typography.bodyLarge)
                            else if (lyrics == null && lyricsFor == n.title) Text("（歌詞庫裡沒找到這首，或還在找）", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { callHim("一起聽", "She is listening to \"$t\"${if (a.isNotBlank()) " by $a" else ""} and wants to listen with you. Use song_lyrics to see where she is, then talk with her about it naturally.") }) { Text("找他一起聽") }
                                if (line != null) TextButton(onClick = { writing = t to line }) { Text("收藏這句") }
                                TextButton(onClick = { writing = t to null }) { Text("寫感受") }
                            }
                            Row {
                                TextButton(onClick = {
                                    if (playlist.none { it.title.equals(t, true) }) OurPlaylist.update(context) { it + PlaylistSong(title = t, artist = a, by = "her") }
                                }) { Text(if (playlist.any { it.title.equals(t, true) }) "✓ 已在我們的歌單" else "＋ 加進我們的歌單") }
                            }
                        }
                    }
                }
            }
            item { Text("我們的歌單（${playlist.size}）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (playlist.isEmpty()) item { Text("還沒有歌。可以把正在聽的加進來，或請他推薦。", style = MaterialTheme.typography.bodySmall) }
            items(playlist, key = { "p" + it.id }) { song ->
                Row(Modifier.fillMaxWidth().clickable { openOnYtMusic(context, song.title, song.artist) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("▶ ${song.title}" + if (song.artist.isNotBlank()) " - ${song.artist}" else "", style = MaterialTheme.typography.bodyMedium)
                        Text((if (song.by == "him") "他加的" else "你加的") + if (song.note.isNotBlank()) "：${song.note}" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { OurPlaylist.update(context) { l -> l.filterNot { it.id == song.id } } }) { Text("移除") }
                }
            }
            item { Text("批註本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
            if (notes.isEmpty()) item { Text("每首歌兩個人都能寫一句感受、收藏喜歡的歌詞。", style = MaterialTheme.typography.bodySmall) }
            items(notes.take(50), key = { "n" + it.id }) { n ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${n.title}・${if (n.by == "him") "他" else "你"}・" + Instant.ofEpochMilli(n.at).atZone(ZoneId.systemDefault()).format(LISTEN_FMT), style = MaterialTheme.typography.labelMedium)
                        n.line?.let { Text("「$it」", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                        if (n.text.isNotBlank()) Text(n.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { Text("最近聽過", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
            if (history.isEmpty()) item { Text("打開通知存取後，你在 YouTube／YT Music 播的歌會自動記在這裡，他也看得到。", style = MaterialTheme.typography.bodySmall) }
            items(history.take(40), key = { "h" + it.at }) { h ->
                Text(
                    Instant.ofEpochMilli(h.at).atZone(ZoneId.systemDefault()).format(LISTEN_FMT) + "  " + MusicText.cleanTitle(h.title) +
                        (if (h.artist.isNotBlank()) " - ${MusicText.cleanArtist(h.artist)}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clickable { openOnYtMusic(context, h.title, h.artist) }.padding(vertical = 2.dp),
                )
            }
        }
    }

    writing?.let { (title, line) ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { writing = null },
            title = { Text(if (line != null) "收藏這句" else "寫下感受") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    line?.let { Text("「$it」", style = MaterialTheme.typography.bodyMedium) }
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(if (line != null) "想說的話（可空白）" else "這首歌讓你想到…") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val now2 = now
                    SongNotes.update(context) { listOf(SongNote(title = title, artist = MusicText.split(now2?.title ?: title, now2?.artist ?: "").second, by = "her", text = text.trim(), line = line)) + it }
                    writing = null
                }, enabled = line != null || text.isNotBlank()) { Text("存下來") }
            },
            dismissButton = { TextButton(onClick = { writing = null }) { Text("取消") } },
        )
    }
}
