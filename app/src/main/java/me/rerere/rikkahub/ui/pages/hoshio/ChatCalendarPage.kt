package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 白天用主題的主色（藍綠），晚上用星星金 */
private val GOLD: Color
    @Composable get() = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) Color(0xFFC9AE78) else MaterialTheme.colorScheme.primary.copy(alpha = .55f)
private val GOLD_DEEP: Color
    @Composable get() = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) Color(0xFFA88A4E) else MaterialTheme.colorScheme.primary

/** 一天的對話統計 */
private data class ChatDayStat(
    val date: LocalDate,
    val minutes: Long = 0,
    val first: Long? = null,
    val last: Long? = null,
    val herCount: Int = 0,
    val hisCount: Int = 0,
    val proactive: Int = 0,
    val tokens: Long = 0,
)

/** 兩則訊息間隔在這之內，算作一直在聊 */
private const val CHAT_GAP_MS = 30 * 60_000L

/**
 * 星汐「對話日曆」：每天聊了多久、最早最晚、各說了幾句、他主動找你幾次、用了多少 token。
 */
@Composable
fun ChatCalendarPage() {
    PlayScaffold("對話日曆") { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
                ChatCalendarContent(Modifier.padding(16.dp))
            }
            Text("顏色越深，那天聊得越多。點日期看細節。聊天時間是把間隔 30 分鐘內的訊息算成連續在聊。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 日曆本體（月份切換、格子、總計、點選那天的細節），首頁和日曆頁共用 */
@Composable
fun ChatCalendarContent(modifier: Modifier = Modifier) {
    val repo = koinInject<ConversationRepository>()
    val settingsStore = koinInject<SettingsStore>()
    val usageDao = koinInject<UsageRecordDAO>()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var stats by remember { mutableStateOf<Map<LocalDate, ChatDayStat>?>(null) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    var names by remember { mutableStateOf("你" to "他") }

    LaunchedEffect(month) {
        stats = null
        stats = withContext(Dispatchers.IO) {
            runCatching {
                val settings = settingsStore.awaitLoaded()
                val assistant = settings.assistants.firstOrNull { it.id == settings.assistantId }
                names = settings.displaySetting.userNickname.ifBlank { "你" } to (assistant?.name?.ifBlank { null } ?: "他")
                computeMonth(repo, usageDao, settings.assistantId, month)
            }.getOrElse { emptyMap() }
        }
    }

    // 首頁的毛玻璃卡片沒有設定文字顏色，這裡明確給，暗色模式才不會變成深色字
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1); selected = null }) { Text("‹", fontSize = 26.sp) }
            Text("${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(enabled = month < YearMonth.now(), onClick = { month = month.plusMonths(1); selected = null }) { Text("›", fontSize = 26.sp) }
        }
        Row(Modifier.fillMaxWidth()) {
            "日一二三四五六".forEach {
                Text(it.toString(), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val s = stats
        if (s == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            MonthGrid(month, s, selected) { selected = if (selected == it) null else it }
            selected?.let { d -> DayDetail(s[d] ?: ChatDayStat(d), names) }
            val active = s.values.filter { it.herCount + it.hisCount > 0 }
            val totalMin = active.sumOf { it.minutes }
            Text(
                "活躍 ${active.size} 天　一起聊了 ${totalMin / 60} 小時 ${totalMin % 60} 分　${names.second} 主動找你 ${active.sumOf { it.proactive }} 次",
                style = MaterialTheme.typography.bodySmall,
            )
            val tokens = s.values.sumOf { it.tokens }
            if (tokens > 0) Text("這個月用了 ${formatTokens(tokens)} tokens（全部請求，含標題、翻譯等）", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, stats: Map<LocalDate, ChatDayStat>, selected: LocalDate?, onPick: (LocalDate) -> Unit) {
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value % 7 // 週日開頭
    val cells = (0 until lead).map { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    // 依那天的訊息數量決定深淺
    val maxCount = stats.values.maxOfOrNull { it.herCount + it.hisCount }?.coerceAtLeast(10) ?: 10
    val empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 7).forEach { i ->
                    val date = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1.2f)) {
                        if (date != null) {
                            val st = stats[date]
                            val active = st != null && st.herCount + st.hisCount > 0
                            val t = if (active) (0.25f + 0.75f * ((st!!.herCount + st.hisCount).toFloat() / maxCount)).coerceIn(0.25f, 1f) else 0f
                            // 最淺的也要夠深，字色才能統一
                            val bg = if (active) lerp(GOLD, GOLD_DEEP, t) else empty
                            Box(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(bg)
                                    .then(if (date == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                                    .clickable { onPick(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    // 有聊天的格子字色統一：白天白字、晚上深色字（金色底配深字比較清楚）
                                    color = if (!active) MaterialTheme.colorScheme.onSurface else if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) Color(0xFF231C10) else Color.White,
                                    fontWeight = if (date == LocalDate.now()) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayDetail(d: ChatDayStat, names: Pair<String, String>) {
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    val zone = ZoneId.systemDefault()
    Card(colors = CardDefaults.cardColors(containerColor = GOLD.copy(alpha = .16f), contentColor = MaterialTheme.colorScheme.onSurface), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.date.toString(), style = MaterialTheme.typography.titleSmall, color = GOLD_DEEP, fontWeight = FontWeight.SemiBold)
            if (d.herCount + d.hisCount == 0) {
                Text("這天沒有聊天", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("聊了 ${d.minutes / 60} 小時 ${d.minutes % 60} 分", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (d.first != null && d.last != null) {
                    Text("最早 ${Instant.ofEpochMilli(d.first).atZone(zone).format(hm)}・最晚 ${Instant.ofEpochMilli(d.last).atZone(zone).format(hm)}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${names.first} 說了 ${d.herCount} 句、${names.second} 回了 ${d.hisCount} 句", style = MaterialTheme.typography.bodyMedium)
                if (d.proactive > 0) Text("${names.second} 主動找你 ${d.proactive} 次", style = MaterialTheme.typography.bodyMedium)
            }
            if (d.tokens > 0) Text("用了 ${formatTokens(d.tokens)} tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

private suspend fun computeMonth(
    repo: ConversationRepository,
    usageDao: UsageRecordDAO,
    assistantId: kotlin.uuid.Uuid,
    month: YearMonth,
): Map<LocalDate, ChatDayStat> {
    val zone = ZoneId.systemDefault()
    val tz = TimeZone.currentSystemDefault()
    val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    // (時間, 角色, 是否為自動事件)
    data class Ev(val at: Long, val role: MessageRole, val notice: Boolean)
    val events = repo.getRecentConversations(assistantId, limit = 60)
        .flatMap { c -> c.currentMessages }
        .mapNotNull { m ->
            val at = runCatching { m.createdAt.toInstant(tz).toEpochMilliseconds() }.getOrNull() ?: return@mapNotNull null
            if (at !in from until to) return@mapNotNull null
            when {
                m.role == MessageRole.USER && m.isNoticeOnly -> Ev(at, MessageRole.USER, true)
                m.role == MessageRole.USER && !m.isSynthetic -> Ev(at, MessageRole.USER, false)
                m.role == MessageRole.ASSISTANT -> Ev(at, MessageRole.ASSISTANT, false)
                else -> null
            }
        }
        .sortedBy { it.at }

    val tokensByDay = runCatching { usageDao.getTotalsByBucket("%Y-%m-%d", from) }.getOrDefault(emptyList())
        .associate { it.bucket to it.promptTokens + it.completionTokens }

    val byDay = events.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
    val result = mutableMapOf<LocalDate, ChatDayStat>()
    (1..month.lengthOfMonth()).map { month.atDay(it) }.forEach { date ->
        val list = byDay[date].orEmpty()
        val talk = list.filter { !it.notice }
        var minutes = 0L
        talk.zipWithNext().forEach { (a, b) -> if (b.at - a.at <= CHAT_GAP_MS) minutes += (b.at - a.at) }
        // 自動事件之後他真的開口了，才算主動找你
        val proactive = list.withIndex().count { (i, e) -> e.notice && list.getOrNull(i + 1)?.role == MessageRole.ASSISTANT }
        result[date] = ChatDayStat(
            date = date,
            minutes = minutes / 60_000,
            first = talk.firstOrNull()?.at,
            last = talk.lastOrNull()?.at,
            herCount = talk.count { it.role == MessageRole.USER },
            hisCount = talk.count { it.role == MessageRole.ASSISTANT },
            proactive = proactive,
            tokens = tokensByDay[date.toString()] ?: 0,
        )
    }
    return result
}
