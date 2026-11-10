package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.ImageAdd01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.hoshio.care.CarePrefs
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.compose.koinInject
import java.time.LocalTime
import kotlin.uuid.Uuid

private data class HomeLink(val title: String, val subtitle: String, val icon: ImageVector, val screen: () -> Screen)

/**
 * 星汐首页：打开 App 先来这里，可以从这里去所有页面。
 */
@Composable
fun HomePage() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val conversationRepository = koinInject<ConversationRepository>()
    val prefs = remember { CarePrefs(context) }
    val assistantId = settings.assistantId
    val assistant = settings.assistants.firstOrNull { it.id == assistantId } ?: settings.assistants.firstOrNull()
    val assistantName = assistant?.name?.ifBlank { null } ?: "時澈"
    val nickname = settings.displaySetting.userNickname.ifBlank { null }

    val latest by produceState<Pair<Uuid, String>?>(null, assistantId) {
        value = runCatching {
            conversationRepository.getRecentConversations(assistantId, limit = 1).firstOrNull()?.let { conversation ->
                val lastReply = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    ?.toText()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
                conversation.id to lastReply
            }
        }.getOrNull()
    }

    // 认识第几天、聊了几则讯息（没设定认识日时，从第一段对话算起）
    val stats by produceState<Pair<Long, Int>?>(null, assistantId) {
        value = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val conversations = conversationRepository.getRecentConversations(assistantId, limit = 500)
                val messages = conversations.sumOf { c ->
                    c.currentMessages.count { (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && !it.isNoticeOnly }
                }
                val start = me.rerere.rikkahub.hoshio.anniversary.AnniversaryStore.metDate(context)
                    ?: conversations.minOfOrNull { it.createAt }?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
                    ?: java.time.LocalDate.now()
                (java.time.temporal.ChronoUnit.DAYS.between(start, java.time.LocalDate.now()) + 1) to messages
            }
        }.getOrNull()
    }

    val greeting = remember {
        when (LocalTime.now().hour) {
            in 5..10 -> "早安"
            in 11..13 -> "午安"
            in 14..17 -> "午後好"
            in 18..22 -> "晚上好"
            else -> "這麼晚了還醒著"
        }
    }

    // 回到和他最近的那段对话（主动关心的讯息也在那里）；还没聊过就开新的
    fun openChat() {
        navigateToChatPage(navigator = navController, chatId = latest?.first ?: Uuid.random())
    }

    val assistantKey = (assistant?.id ?: assistantId).toString()
    val links = listOf(
        HomeLink("日記", "他每天寫的日記，也能自己寫", HugeIcons.Book01) { Screen.Diary },
        HomeLink("遊戲室", "他做的小遊戲和小網頁", HugeIcons.Puzzle) { Screen.Playroom },
        HomeLink("書房", "一起讀書、寫批註", HugeIcons.Book02) { Screen.Library },
        HomeLink("紀念日", "重要的日子和認識幾天", HugeIcons.Calendar03) { Screen.Anniversary },
        HomeLink("他的隱私室", "他的心事，要申請才看得到", HugeIcons.Key01) { Screen.PrivateRoom },
        HomeLink("對話紀錄", "所有聊過的對話", HugeIcons.TransactionHistory) { Screen.History },
        HomeLink("$assistantName 的設定", "個性、模型、頭像", HugeIcons.UserCircle) { Screen.AssistantDetail(assistantKey) },
        HomeLink("主動關心與工具", "地點、偷看螢幕、自由時間", HugeIcons.Notification01) { Screen.AssistantLocalTool(assistantKey) },
        HomeLink("記憶", "他記得的關於你的事", HugeIcons.AiBrain01) { Screen.AssistantMemory(assistantKey) },
        HomeLink("收藏", "收藏的訊息", HugeIcons.Favourite) { Screen.Favorite },
        HomeLink("媒體創作", "生成圖片與影片", HugeIcons.ImageAdd01) { Screen.MediaCreationSessions },
        HomeLink("翻譯", "翻譯工具", HugeIcons.Translate) { Screen.Translator },
        HomeLink("備份與還原", "把資料存到雲端", HugeIcons.CloudUpload) { Screen.Backup },
        HomeLink("外觀主題", "星空主題、深色模式", HugeIcons.PaintBoard) { Screen.SettingPreferencesTheme },
        HomeLink("設定", "服務商、模型、外觀", HugeIcons.Settings03) { Screen.Setting },
    )

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (nickname != null) "$greeting，$nickname" else greeting,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stats?.let { (days, count) -> "和 $assistantName 認識的第 $days 天・聊了 $count 則訊息" }
                            ?: if (prefs.anyEnabled) "$assistantName 正在陪著你" else "今天想做什麼？",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    onClick = { openChat() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (assistant != null) {
                            UIAvatar(name = assistantName, value = assistant.avatar, modifier = Modifier.size(56.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("和 $assistantName 聊天", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            val preview = latest?.second.orEmpty()
                            Text(
                                text = if (preview.isNotBlank()) "他最後說：$preview" else "點這裡開始聊天",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(HugeIcons.Message01, contentDescription = null)
                    }
                }
            }
            items(links) { link ->
                Card(
                    onClick = { navController.navigate(link.screen()) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(link.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(link.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            link.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
