package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.ui.pages.hoshio.splash.drawSparkle
import me.rerere.rikkahub.ui.pages.hoshio.splash.HoshioGlass
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import me.rerere.hugeicons.stroke.Sparkles
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
            in 18..22 -> "晚安"
            else -> "夜深了"
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
        HomeLink("atlas", "記憶星圖", "把他記得的事畫成星系", HugeIcons.Sparkles, SECTION_HIM) { Screen.MemoryAtlas },
        HomeLink("diary", "日記與心情", "每天的日記和心情打卡", HugeIcons.Book01, SECTION_US) { Screen.Diary },
        HomeLink("playhub", "一起玩", "看螢幕、下棋、海龜湯、每日一問、寵物", HugeIcons.Puzzle, SECTION_US) { Screen.PlayHub },
        HomeLink("memories", "我們的回憶", "相簿、約定、紀念日、時光膠囊", HugeIcons.Calendar03, SECTION_US) { Screen.MemoriesHub },
        HomeLink("health", "手錶與健康", "睡眠、心率、步數", HugeIcons.Favourite, SECTION_US) { Screen.Health },
        HomeLink("library", "書房", "一起讀書、寫批註", HugeIcons.Book02, SECTION_US) { Screen.Library },
        HomeLink("wakerules", "他的喚醒規則", "他自己訂什麼時候醒來找你", HugeIcons.Clock01, SECTION_HIM) { Screen.WakeRules },
        HomeLink("sleep", "晚安", "要睡了就來這裡，他會安靜陪你", HugeIcons.Moon02, SECTION_HIM) { Screen.Sleep },
        HomeLink("routine", "生活照顧", "喝水、吃飯、吃藥提醒", HugeIcons.Clock01, SECTION_US) { Screen.Routine },
        HomeLink("history", "對話紀錄", "所有聊過的對話", HugeIcons.TransactionHistory, SECTION_US) { Screen.History },
        HomeLink("favorite", "收藏", "收藏的訊息", HugeIcons.Favourite, SECTION_US) { Screen.Favorite },
        HomeLink("assistant", "$assistantName 的設定", "個性、模型、頭像", HugeIcons.UserCircle, SECTION_TOOLS) { Screen.AssistantDetail(assistantKey) },
        HomeLink("tools", "主動關心與工具", "地點、偷看螢幕、自由時間", HugeIcons.Notification01, SECTION_TOOLS) { Screen.AssistantLocalTool(assistantKey) },
        HomeLink("handoff", "對話交接", "對話太長時換新對話接著聊", HugeIcons.TransactionHistory, SECTION_TOOLS) { Screen.Handoff },
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

    // 「在一起的日子」热力图：最近 20 周每天她传了几则讯息；另外算总对话数、连续天数、本周讯息
    val conversationDao = koinInject<me.rerere.rikkahub.data.db.dao.ConversationDAO>()
    val messageDao = koinInject<me.rerere.rikkahub.data.db.dao.MessageNodeDAO>()
    val today = remember { java.time.LocalDate.now() }
    val heat by produceState(HomeHeat(), assistantId) {
        value = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val start = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY))
                    .minusWeeks((HEATMAP_WEEKS - 1).toLong())
                val perDay = messageDao.getMessageCountPerDay(start.toString())
                    .mapNotNull { e -> runCatching { java.time.LocalDate.parse(e.day) to e.count }.getOrNull() }.toMap()
                HomeHeat(true, conversationDao.countAll(), perDay)
            }
        }.getOrElse { HomeHeat(loaded = true) }
    }

    val dark = me.rerere.rikkahub.ui.theme.LocalDarkMode.current
    val haze = dev.chrisbanes.haze.rememberHazeState()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val hoshioTheme = settings.themeId == "hoshio" && !settings.dynamicColor

    Box(Modifier.fillMaxSize().background(if (hoshioTheme) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background)) {
        // 星汐主题：白天淺海焦散、晚上星空；同时当作毛玻璃的模糊来源
        if (hoshioTheme) {
            me.rerere.rikkahub.ui.pages.hoshio.splash.HoshioChatBackdrop(
                dark = dark,
                animate = true,
                modifier = Modifier.fillMaxSize().then(Modifier.hazeSource(haze)),
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(
            me.rerere.rikkahub.ui.pages.hoshio.splash.LocalHoshioHaze provides (if (hoshioTheme) haze else null),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 页首：HOSHIO · 星汐／晚安，YAN
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.statusBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("HOSHIO · 星汐", fontSize = 11.sp, letterSpacing = 3.sp, color = accent)
                            Text(
                                if (nickname != null) "$greeting，$nickname" else greeting,
                                fontSize = 24.sp,
                                fontFamily = FontFamily.Serif,
                                color = ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // 最大张：和他聊天
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HoshioGlass(Modifier.fillMaxWidth().clickable { openChat() }, shape = RoundedCornerShape(28.dp), strong = true) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (assistant != null) UIAvatar(name = assistantName, value = assistant.avatar, modifier = Modifier.size(56.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("和 $assistantName 聊天", fontSize = 20.sp, fontFamily = FontFamily.Serif, color = ink)
                                    Text(if (prefs.anyEnabled) "他正在陪著你" else "繼續剛才的話題", fontSize = 12.sp, color = muted)
                                }
                                Icon(HugeIcons.Message01, contentDescription = null, tint = accent)
                            }
                            val preview = latest?.second.orEmpty()
                            Text(
                                text = if (preview.isNotBlank()) "「$preview」" else "點這裡開始聊天",
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                color = ink,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // 在一起的第 N 天
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HoshioGlass(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("和 $assistantName 在一起的第", fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(stats?.first?.toString() ?: "—", fontSize = 54.sp, lineHeight = 58.sp, fontFamily = FontFamily.Serif, color = ink)
                                    Text("天", fontSize = 18.sp, fontFamily = FontFamily.Serif, color = ink, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
                                }
                                stats?.let { (days, _) ->
                                    val start = today.minusDays(days - 1)
                                    Text("從 ${start.year} 年 ${start.monthValue} 月 ${start.dayOfMonth} 日開始", fontSize = 12.sp, color = muted)
                                }
                            }
                            HomeSparkleMark(Modifier.size(64.dp), accent)
                        }
                    }
                }
                // 统计小卡
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeStatChip("${stats?.second ?: "—"}", "則訊息", Modifier.weight(1f))
                        HomeStatChip("${heat.conversations}", "段對話", Modifier.weight(1f))
                        HomeStatChip("${heat.streak(today)}", "天連續", Modifier.weight(1f))
                        HomeStatChip("${heat.weekMessages(today)}", "則本週", Modifier.weight(1f))
                    }
                }
                // 功能入口（毛玻璃小卡，依「他／我們／设定与工具」分区，可以整理隐藏）
                listOf(SECTION_HIM, SECTION_US, SECTION_TOOLS).forEach { section ->
                    val sectionLinks = links.filter { it.section == section && (editing || it.key !in hidden) }
                    if (sectionLinks.isEmpty()) return@forEach
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(section, fontSize = 12.sp, letterSpacing = 2.sp, color = accent, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                    }
                    items(sectionLinks, key = { it.key }) { link ->
                        val isHidden = link.key in hidden
                        HoshioGlass(
                            Modifier.heightIn(min = 76.dp).clickable { if (editing) toggleHidden(link.key) else navController.navigate(link.screen()) },
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).graphicsLayer { alpha = if (editing && isHidden) .4f else 1f }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(link.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                                    Text(
                                        "  " + link.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    if (editing) (if (isHidden) "已隱藏・點一下顯示" else "顯示中・點一下隱藏") else link.subtitle,
                                    fontSize = 11.sp,
                                    color = muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                        }
                    }
                }
                // 在一起的日子
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HoshioGlass(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("在一起的日子", fontSize = 16.sp, fontFamily = FontFamily.Serif, color = ink)
                                Box(Modifier.weight(1f))
                                Text("最近 $HEATMAP_WEEKS 週", fontSize = 11.sp, color = muted)
                            }
                            HomeHeatmap(heat.perDay, today, accent, ink, Modifier.fillMaxWidth().padding(top = 12.dp))
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("少", fontSize = 10.sp, color = muted)
                                listOf(0f, .3f, .55f, .8f, 1f).forEach { level ->
                                    androidx.compose.foundation.Canvas(Modifier.padding(start = 4.dp).size(10.dp)) {
                                        drawRoundRect(heatColor(level, accent, ink), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                                    }
                                }
                                Text("  多", fontSize = 10.sp, color = muted)
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    androidx.compose.material3.TextButton(onClick = { editing = !editing }) {
                        Text(if (editing) "完成" else "整理首頁（隱藏不常用的）", color = accent)
                    }
                }
            }
        }
    }
}

private const val HEATMAP_WEEKS = 20

private data class HomeHeat(
    val loaded: Boolean = false,
    val conversations: Int = 0,
    val perDay: Map<java.time.LocalDate, Int> = emptyMap(),
) {
    fun weekMessages(today: java.time.LocalDate): Int = (0L..6L).sumOf { perDay[today.minusDays(it)] ?: 0 }

    /** 连续有聊天的天数；今天还没聊时从昨天往回算 */
    fun streak(today: java.time.LocalDate): Int {
        var day = if ((perDay[today] ?: 0) > 0) today else today.minusDays(1)
        var count = 0
        while ((perDay[day] ?: 0) > 0) { count++; day = day.minusDays(1) }
        return count
    }
}

@Composable
private fun HomeStatChip(value: String, label: String, modifier: Modifier) {
    HoshioGlass(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 20.sp, fontFamily = FontFamily.Serif, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun HomeSparkleMark(modifier: Modifier, color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val c = androidx.compose.ui.geometry.Offset(size.width * .55f, size.height * .45f)
        drawCircle(color.copy(alpha = .10f), size.minDimension * .42f, c)
        drawSparkle(c, size.minDimension * .34f, color.copy(alpha = .85f))
        drawSparkle(androidx.compose.ui.geometry.Offset(size.width * .2f, size.height * .82f), size.minDimension * .1f, color.copy(alpha = .6f))
        drawSparkle(androidx.compose.ui.geometry.Offset(size.width * .9f, size.height * .12f), size.minDimension * .08f, color.copy(alpha = .5f))
    }
}

private fun heatColor(level: Float, accent: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color) =
    if (level <= 0f) ink.copy(alpha = .08f) else accent.copy(alpha = .25f + .7f * level.coerceIn(0f, 1f))

@Composable
private fun HomeHeatmap(
    perDay: Map<java.time.LocalDate, Int>,
    today: java.time.LocalDate,
    accent: androidx.compose.ui.graphics.Color,
    ink: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    val start = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY)).minusWeeks((HEATMAP_WEEKS - 1).toLong())
    val max = (perDay.values.maxOrNull() ?: 0).coerceAtLeast(1)
    androidx.compose.foundation.Canvas(modifier.aspectRatio(HEATMAP_WEEKS / 7.2f)) {
        val gap = 3.dp.toPx()
        val cell = ((size.width - gap * (HEATMAP_WEEKS - 1)) / HEATMAP_WEEKS).coerceAtMost((size.height - gap * 6) / 7)
        val radius = androidx.compose.ui.geometry.CornerRadius(cell * .22f)
        for (w in 0 until HEATMAP_WEEKS) for (d in 0 until 7) {
            val date = start.plusWeeks(w.toLong()).plusDays(d.toLong())
            if (date.isAfter(today)) continue
            val count = perDay[date] ?: 0
            val level = if (count == 0) 0f else (.15f + .85f * count / max.toFloat())
            val topLeft = androidx.compose.ui.geometry.Offset(w * (cell + gap), d * (cell + gap))
            val cellSize = androidx.compose.ui.geometry.Size(cell, cell)
            drawRoundRect(heatColor(level, accent, ink), topLeft, cellSize, radius)
            if (date == today) drawRoundRect(accent, topLeft, cellSize, radius, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }
}
