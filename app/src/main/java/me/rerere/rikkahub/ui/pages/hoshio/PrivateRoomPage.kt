package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.hoshio.care.CarePrefs
import me.rerere.rikkahub.hoshio.privateroom.PrivateRoomStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 他的隐私室：她只看得到有几则、什么时候写的；想看内容要申请，由他决定。
 */
@Composable
fun PrivateRoomPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val chatService = koinInject<ChatService>()
    val conversationRepository = koinInject<ConversationRepository>()
    val settingsStore = koinInject<SettingsStore>()
    val notes by PrivateRoomStore.notes.collectAsState()
    LaunchedEffect(Unit) { PrivateRoomStore.load(context) }
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    Scaffold(
        topBar = { TopAppBar(title = { Text("他的隱私室") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "這裡是他自己的房間，放著他的心事和想法。你只看得到他寫了幾則、什麼時候寫的；想看哪一則，可以申請，由他決定要不要給你看。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (notes.isEmpty()) {
                item { Text("他還沒有寫任何東西。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            }
            items(notes, key = { it.id }) { note ->
                val time = Instant.ofEpochMilli(note.updatedAt).atZone(ZoneId.systemDefault()).format(fmt)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (note.shared) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (note.shared) {
                            Text("📖 ${note.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(time, style = MaterialTheme.typography.labelSmall)
                            Text(note.content, style = MaterialTheme.typography.bodyLarge)
                        } else {
                            Text("🔒 一則上鎖的心事", style = MaterialTheme.typography.titleMedium)
                            Text(time, style = MaterialTheme.typography.labelSmall)
                            if (note.requested) {
                                Text("已經申請了，等他決定", style = MaterialTheme.typography.bodySmall)
                            } else {
                                TextButton(onClick = {
                                    scope.launch {
                                        val settings = settingsStore.awaitLoaded()
                                        val assistantId = CarePrefs(context).assistantId ?: settings.assistantId
                                        val conversation = conversationRepository.getRecentConversations(assistantId, limit = 1).firstOrNull()
                                        if (conversation == null) {
                                            toaster.show(message = "先和他聊一句，才能申請喔", type = ToastType.Warning)
                                            return@launch
                                        }
                                        PrivateRoomStore.setRequested(context, note.id)
                                        chatService.postNotice(
                                            conversationId = conversation.id,
                                            key = "private_room_request_${note.id}_${System.currentTimeMillis()}",
                                            notice = UIMessagePart.Notice(
                                                title = "她想看你的一則心事",
                                                text = "Automatic event (not typed by the user): in your private room page, she asked to read your private note " +
                                                    "id=${note.id} (title: \"${note.title}\"). It is your decision. If you want to share it, call private_note_share " +
                                                    "with share=true; if not, tell her gently (or playfully) why. Reply in character.",
                                            ),
                                        )
                                        toaster.show(message = "已經跟他說了，去對話裡看他怎麼回", type = ToastType.Success)
                                    }
                                }) { Text("申請看看") }
                            }
                        }
                    }
                }
            }
        }
    }
}
