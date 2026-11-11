package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.hoshio.voice.HerVoiceRecorder
import me.rerere.rikkahub.hoshio.voice.VoicePlayer
import java.io.File

/** 她傳的語音訊息：點一下播放／停止 */
@Composable
fun HerVoiceBubble(url: String) {
    val scope = rememberCoroutineScope()
    val file = remember(url) { runCatching { File(android.net.Uri.parse(url).path!!) }.getOrNull() }
    val seconds by produceState(0, file) {
        value = file?.let { withContext(Dispatchers.IO) { HerVoiceRecorder.durationSec(it.absolutePath) } } ?: 0
    }
    var playing by remember { mutableStateOf<Job?>(null) }
    Card(
        onClick = {
            val job = playing
            if (job != null) {
                job.cancel(); VoicePlayer.stop(); playing = null
            } else if (file?.exists() == true) {
                playing = scope.launch {
                    try { VoicePlayer.play(file) } finally { playing = null }
                }
            }
        },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.widthIn(min = (110 + seconds.coerceIn(0, 30) * 5).dp, max = 260.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (playing != null) "⏸ " else "▶ ") + "🎙️ " + if (seconds > 0) "%d\"".format(seconds) else "語音",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** 輸入框的「按住說話」：放開就把語音傳給他；太短或滑走都不送 */
@Composable
fun HerVoiceNoteButton(
    hasPermission: Boolean,
    requestPermission: () -> Unit,
    onTooShort: () -> Unit,
    onRecorded: (android.net.Uri) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val recorder = remember { HerVoiceRecorder(context) }
    var recordingSince by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(recordingSince) {
        while (recordingSince > 0) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(200)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (recorder.isRecording) recorder.cancel() } }
    val recording = recordingSince > 0
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .androidx_pointer(hasPermission, requestPermission,
                onStart = {
                    if (recorder.start()) recordingSince = System.currentTimeMillis()
                },
                onEnd = { send ->
                    val wasRecording = recorder.isRecording
                    recordingSince = 0
                    if (!wasRecording) return@androidx_pointer
                    if (!send) { recorder.cancel(); return@androidx_pointer }
                    val uri = recorder.stop()
                    if (uri == null) onTooShort() else onRecorded(uri)
                }),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = if (recording) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Transparent,
        ) {
            Text(
                if (recording) "  ${((now - recordingSince).coerceAtLeast(0) / 1000)}\"  " else "🎙️",
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (recording) MaterialTheme.colorScheme.onError else androidx.compose.material3.LocalContentColor.current,
            )
        }
    }
}

/** 按下開始錄，放開送出；手指滑出按鈕範圍放開＝取消 */
private fun Modifier.androidx_pointer(
    hasPermission: Boolean,
    requestPermission: () -> Unit,
    onStart: () -> Unit,
    onEnd: (send: Boolean) -> Unit,
): Modifier = this.then(Modifier.pointerInput(hasPermission) {
    awaitEachGesture {
        val down = awaitFirstDown()
        if (!hasPermission) {
            requestPermission()
            return@awaitEachGesture
        }
        onStart()
        var inside = true
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val p = change.position
            inside = p.x >= -40f && p.y >= -120f && p.x <= size.width + 40f && p.y <= size.height + 40f
            if (!change.pressed) break
            change.consume()
        }
        onEnd(inside)
    }
})
