package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.hoshio.voice.CallLog
import me.rerere.rikkahub.hoshio.voice.CallRecord
import me.rerere.rikkahub.hoshio.voice.VoicePlayer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CALL_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private val LINE_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun dur(sec: Long) = "%d:%02d".format(sec / 60, sec % 60)

/**
 * 星汐「通話紀錄」：每通電話的文字，和他的聲音可以重聽。
 * 語音只留最近 20 通、30 天內；收藏的不會被清掉。
 */
@Composable
fun CallLogPage() {
    val context = LocalContext.current
    val records by CallLog.records.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(0L) }
    LaunchedEffect(records.size) {
        CallLog.load(context)
        size = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CallLog.audioBytes(context) }
    }
    val current = open?.let { id -> records.firstOrNull { it.id == id } }
    if (current != null) {
        CallDetail(current, onBack = { open = null })
        return
    }
    PlayScaffold("通話紀錄") { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "語音只留最近 ${CallLog.KEEP_AUDIO_CALLS} 通、${CallLog.KEEP_AUDIO_DAYS} 天內，⭐ 收藏的不會被清掉；文字一直都在。" +
                        "目前語音佔 ${"%.1f".format(size / 1024.0 / 1024.0)} MB。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (records.isEmpty()) item { Text("還沒有通話紀錄。", style = MaterialTheme.typography.bodyMedium) }
            items(records, key = { it.id }) { r ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { open = r.id },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                (if (r.reason != null) "📞 他打來" else "📱 你打給他") + "・ᯅ ${dur(r.durationSec)}",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                Instant.ofEpochMilli(r.startedAt).atZone(ZoneId.systemDefault()).format(CALL_FMT) +
                                    "・${r.lines.size} 句" + if (r.audioCleared) "・語音已清除" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            r.lines.firstOrNull { it.him }?.let { Text("「${it.text.take(30)}」", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        }
                        TextButton(onClick = { CallLog.setStarred(context, r.id, !r.starred) }) { Text(if (r.starred) "⭐" else "☆") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallDetail(record: CallRecord, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf<Int?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { job?.cancel(); VoicePlayer.stop(); onBack() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { job?.cancel(); VoicePlayer.stop() } }

    fun playFrom(index: Int) {
        job?.cancel()
        VoicePlayer.stop()
        job = scope.launch {
            // 從這句開始，一句一句往下放（只放他的話）
            record.lines.withIndex().drop(index).filter { it.value.him && it.value.audio.isNotEmpty() }.forEach { (i, line) ->
                playing = i
                line.audio.forEach { name ->
                    val f = CallLog.file(context, record.id, name)
                    if (f.exists()) VoicePlayer.play(f)
                }
            }
            playing = null
        }
    }

    PlayScaffold("ᯅ ${dur(record.durationSec)}") { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Instant.ofEpochMilli(record.startedAt).atZone(ZoneId.systemDefault()).format(CALL_FMT) +
                            (record.reason?.let { "・他打來：$it" } ?: "・你打給他"),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    if (!record.audioCleared) TextButton(onClick = { if (playing != null) { job?.cancel(); VoicePlayer.stop(); playing = null } else playFrom(0) }) {
                        Text(if (playing != null) "⏹ 停止" else "▶ 從頭重聽")
                    }
                }
            }
            items(record.lines.size) { i ->
                val line = record.lines[i]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.him) Arrangement.Start else Arrangement.End) {
                    Card(
                        modifier = Modifier.widthIn(max = 300.dp).then(
                            if (line.him && line.audio.isNotEmpty()) Modifier.clickable { playFrom(i) } else Modifier,
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                playing == i -> MaterialTheme.colorScheme.primaryContainer
                                line.him -> MaterialTheme.colorScheme.surfaceContainerHigh
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            },
                        ),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(line.text, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                Instant.ofEpochMilli(line.at).atZone(ZoneId.systemDefault()).format(LINE_FMT) +
                                    if (line.him && line.audio.isNotEmpty()) "・🔊 點一下重聽" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Row {
                    TextButton(onClick = { CallLog.setStarred(context, record.id, !record.starred) }) { Text(if (record.starred) "⭐ 已收藏（語音不會被清掉）" else "☆ 收藏") }
                    TextButton(onClick = { confirmDelete = true }) { Text("刪除這通") }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("刪除這通電話的紀錄？") },
        text = { Text("文字和語音都會刪掉，聊天室裡的對話不受影響。") },
        confirmButton = { TextButton(onClick = { CallLog.delete(context, record.id); confirmDelete = false; onBack() }) { Text("刪除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}
