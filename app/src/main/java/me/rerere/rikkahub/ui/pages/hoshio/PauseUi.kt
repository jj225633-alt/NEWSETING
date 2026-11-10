package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.rules.PauseStore
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

private val PAUSE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")

/** 他暂停对话时，在输入框上方显示：原因、到几点、申请继续 */
@Composable
fun PauseBanner(conversationId: String) {
    val context = LocalContext.current
    val chatService = koinInject<ChatService>()
    val pauses by PauseStore.items.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        PauseStore.load(context)
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val pause = pauses.firstOrNull { it.until > now && (it.scope == "assistant" || it.conversationId == conversationId) } ?: return
    var appealing by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🌙 他想先靜一靜", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(pause.reason, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "到 ${Instant.ofEpochMilli(pause.until).atZone(ZoneId.systemDefault()).format(PAUSE_FMT)} 自動恢復" +
                        if (pause.scope == "assistant") "（所有對話）" else "",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (pause.allowAppeal && pause.appeals < PauseStore.MAX_APPEALS) {
                    TextButton(onClick = { appealing = true }) { Text("申請繼續（${PauseStore.MAX_APPEALS - pause.appeals}）") }
                }
            }
        }
    }
    if (appealing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { appealing = false },
            title = { Text("申請繼續") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("想對他說什麼？") }, minLines = 3) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    PauseStore.countAppeal(context, pause)
                    runCatching {
                        chatService.postNotice(
                            conversationId = Uuid.parse(conversationId),
                            key = "hoshio_appeal:${System.currentTimeMillis()}",
                            notice = UIMessagePart.Notice(
                                title = "她申請繼續",
                                text = "You paused the chat (\"${pause.reason}\"). She asks to continue: \"${text.trim().take(2000)}\". " +
                                    "Decide sincerely: call right_to_refuse action=resume to resume, or keep the pause and tell her gently why.",
                            ),
                        )
                    }
                    appealing = false
                }) { Text("送出") }
            },
            dismissButton = { TextButton(onClick = { appealing = false }) { Text("取消") } },
        )
    }
}
