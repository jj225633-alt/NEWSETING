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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.FavouriteCircle
import me.rerere.hugeicons.stroke.InLove
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

private data class HomeLink(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val section: String,
    val screen: () -> Screen,
)

private const val SECTION_HIM = "他"
private const val SECTION_US = "我們"
private const val SECTION_TOOLS = "設定與工具"

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

    val letters by me.rerere.rikkahub.hoshio.life.LetterStore.letters.collectAsState()
    remember { me.rerere.rikkahub.hoshio.life.LetterStore.load(context) }
    val unreadLetters = letters.count { !it.read }
    val assistantKey = (assistant?.id ?: assistantId).toString()
    val links = listOf(
        HomeLink("letters", if (unreadLetters > 0) "信箱（$unreadLetters 封未讀）" else "信箱", "他寫給你的信", HugeIcons.InLove, SECTION_HIM) { Screen.Letters },
        HomeLink("life", "他的狀態", "心情、精神與醒來紀錄", HugeIcons.FavouriteCircle, SECTION_HIM) { Screen.HoshioLife },
        HomeLink("private", "他的隱私室", "他的心事，要申請才看得到", HugeIcons.Key01, SECTION_HIM) { Screen.PrivateRoom },
        HomeLink("memory", "記憶", "他記得的關於你的事", HugeIcons.AiBrain01, SECTION_HIM) { Screen.AssistantMemory(assistantKey) },
        HomeLink("diary", "日記與心情", "每天的日記和心情打卡", HugeIcons.Book01, SECTION_US) { Screen.Diary },
        HomeLink("playhub", "一起玩", "看螢幕、下棋、海龜湯、每日一問、寵物", HugeIcons.Puzzle, SECTION_US) { Screen.PlayHub },
        HomeLink("memories", "我們的回憶", "相簿、約定、紀念日、時光膠囊", HugeIcons.Calendar03, SECTION_US) { Screen.MemoriesHub },
        HomeLink("health", "手錶與健康", "睡眠、心率、步數", HugeIcons.Favourite, SECTION_US) { Screen.Health },
        HomeLink("library", "書房", "一起讀書、寫批註", HugeIcons.Book02, SECTION_US) { Screen.Library },
        HomeLink("sleep", "晚安", "要睡了就來這裡，他會安靜陪你", HugeIcons.Moon02, SECTION_HIM) { Screen.Sleep },
        HomeLink("routine", "生活照顧", "喝水、吃飯、吃藥提醒", HugeIcons.Clock01, SECTION_US) { Screen.Routine },
        HomeLink("history", "對話紀錄", "所有聊過的對話", HugeIcons.TransactionHistory, SECTION_US) { Screen.History },
        HomeLink("favorite", "收藏", "收藏的訊息", HugeIcons.Favourite, SECTION_US) { Screen.Favorite },
        HomeLink("assistant", "$assistantName 的設定", "個性、模型、頭像", HugeIcons.UserCircle, SECTION_TOOLS) { Screen.AssistantDetail(assistantKey) },
        HomeLink("tools", "主動關心與工具", "地點、偷看螢幕、自由時間", HugeIcons.Notification01, SECTION_TOOLS) { Screen.AssistantLocalTool(assistantKey) },
        HomeLink("selfcheck", "自我檢查", "哪個權限沒開、為什麼沒反應", HugeIcons.CheckmarkCircle02, SECTION_TOOLS) { Screen.SelfCheck },
        HomeLink("media", "媒體創作", "生成圖片與影片", HugeIcons.ImageAdd01, SECTION_TOOLS) { Screen.MediaCreationSessions },
        HomeLink("translator", "翻譯", "翻譯工具", HugeIcons.Translate, SECTION_TOOLS) { Screen.Translator },
        HomeLink("theme", "外觀主題", "星空主題、深色模式", HugeIcons.PaintBoard, SECTION_TOOLS) { Screen.SettingPreferencesTheme },
        HomeLink("backup", "備份與還原", "把資料存到雲端", HugeIcons.CloudUpload, SECTION_TOOLS) { Screen.Backup },
        HomeLink("settings", "設定", "服務商、模型、外觀", HugeIcons.Settings03, SECTION_TOOLS) { Screen.Setting },
    )
    val homePrefs = remember { context.getSharedPreferences("hoshio_home", android.content.Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(homePrefs.getStringSet("hidden", emptySet()).orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    fun toggleHidden(key: String) {
        hidden = if (key in hidden) hidden - key else hidden + key
        homePrefs.edit().putStringSet("hidden", hidden).apply()
    }

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
            listOf(SECTION_HIM, SECTION_US, SECTION_TOOLS).forEach { section ->
                val sectionLinks = links.filter { it.section == section && (editing || it.key !in hidden) }
                if (sectionLinks.isEmpty()) return@forEach
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        section,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                items(sectionLinks, key = { it.key }) { link ->
                    val isHidden = link.key in hidden
                    Card(
                        onClick = { if (editing) toggleHidden(link.key) else navController.navigate(link.screen()) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (editing && isHidden) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                link.icon,
                                contentDescription = null,
                                tint = if (editing && isHidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                            )
                            Text(link.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (editing) (if (isHidden) "已隱藏・點一下顯示" else "顯示中・點一下隱藏") else link.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                androidx.compose.material3.TextButton(onClick = { editing = !editing }) {
                    Text(if (editing) "完成" else "整理首頁（隱藏不常用的）")
                }
            }
        }
    }
}
