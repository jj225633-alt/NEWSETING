package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.hoshio.voice.VoiceAudio
import me.rerere.rikkahub.hoshio.voice.VoicePlayer
import java.io.File

/**
 * 星汐: 他的语音讯息。点一下播放／停止，点「文字」看他说了什么。
 */
@Composable
fun VoiceBubble(file: String, seconds: Int, text: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf<Job?>(null) }
    var showText by remember { mutableStateOf(false) }
    val audio = remember(file) { File(VoiceAudio.dir(context), file) }
    val exists = remember(file) { audio.exists() }
    // 泡泡长度随秒数变长一点
    val width = (120 + seconds.coerceIn(0, 30) * 5).dp

    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Card(
            onClick = {
                val job = playing
                if (job != null) {
                    job.cancel()
                    VoicePlayer.stop()
                    playing = null
                } else if (exists) {
                    playing = scope.launch {
                        try {
                            VoicePlayer.play(audio)
                        } finally {
                            playing = null
                        }
                    }
                }
            },
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.widthIn(min = width, max = 280.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (playing != null) "⏸" else "▶", fontSize = 18.sp)
                Row(Modifier.padding(horizontal = 10.dp).weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 简单的声波
                    listOf(8, 14, 20, 12, 18, 10, 16, 8, 14, 6).forEach { h ->
                        androidx.compose.foundation.layout.Box(
                            Modifier.width(3.dp).height(h.dp).background(
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = if (playing != null) .9f else .55f),
                                RoundedCornerShape(2.dp),
                            ),
                        )
                    }
                }
                Text(if (exists) "${seconds.coerceAtLeast(1)}″" else "已失效", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (text.isNotBlank()) {
            Text(
                if (showText) "收起文字" else "🎙️ 語音訊息・看文字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showText = !showText }.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            AnimatedVisibility(showText) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
