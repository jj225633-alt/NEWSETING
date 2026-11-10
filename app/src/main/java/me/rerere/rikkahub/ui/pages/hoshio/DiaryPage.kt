package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.rikkahub.hoshio.care.CarePrefs
import me.rerere.rikkahub.hoshio.care.CareWorker
import me.rerere.rikkahub.hoshio.diary.DiaryAuthor
import me.rerere.rikkahub.hoshio.diary.DiaryEntry
import me.rerere.rikkahub.hoshio.diary.DiaryStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.toast.ToastType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 星汐的日记本：他每天写的日记，以及你自己想写的日记（可以选择要不要让他看）。
 */
@Composable
fun DiaryPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val prefs = remember { CarePrefs(context) }
    val entries by DiaryStore.entries.collectAsState()
    var diaryEnabled by remember { mutableStateOf(prefs.diaryEnabled) }
    var diaryHour by remember { mutableStateOf(prefs.diaryWriteHour) }
    var editing by remember { mutableStateOf<DiaryEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DiaryEntry?>(null) }
    var calendarMode by remember { mutableStateOf(true) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var newEntryDate by remember { mutableStateOf<String?>(null) }
    var moodPicking by remember { mutableStateOf<LocalDate?>(null) }
    val moods by me.rerere.rikkahub.hoshio.together.MoodCheckinStore.items.collectAsState()
    val moodByDate = remember(moods) { moods.associateBy { it.date } }

    LaunchedEffect(Unit) {
        DiaryStore.load(context)
        me.rerere.rikkahub.hoshio.together.MoodCheckinStore.load(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日記與心情") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(HugeIcons.QuillWrite01, null) },
                text = { Text("寫日記") },
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val todayMood = moodByDate[LocalDate.now().toString()]
                Card(
                    onClick = { moodPicking = LocalDate.now() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("今天的心情", style = MaterialTheme.typography.labelLarge)
                            Text(
                                todayMood?.let { "${it.emoji} ${it.label}" } ?: "點這裡打卡 ✨",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!todayMood?.note.isNullOrBlank()) Text(todayMood!!.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("讓他每天寫日記", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "每晚 $diaryHour:00 後，他會依照當天和你的對話寫一篇；忘了寫的話隔天會補上。那天沒聊天就不寫。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = diaryEnabled,
                                onCheckedChange = {
                                    prefs.diaryEnabled = it
                                    diaryEnabled = it
                                    CareWorker.sync(context)
                                },
                            )
                        }
                        if (diaryEnabled) {
                            NumberChoiceChips(
                                options = listOf(20, 21, 22, 23),
                                selected = diaryHour,
                                label = { "$it:00 後寫" },
                                range = 12..23,
                                unit = "點",
                            ) { diaryHour = it; prefs.diaryWriteHour = it }
                        }
                        TextButton(onClick = {
                            CareWorker.writeDiaryNow(context)
                            toaster.show(message = "請他現在寫今天的日記，寫好會通知你", type = ToastType.Success)
                        }) { Text("現在寫今天的日記") }
                    }
                }
            }
            item {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = calendarMode,
                        onClick = { calendarMode = true },
                        label = { Text("月曆") },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = !calendarMode,
                        onClick = { calendarMode = false },
                        label = { Text("列表") },
                    )
                }
            }
            if (calendarMode) {
                item {
                    DiaryCalendar(entries = entries, moods = moodByDate, onDayClick = { selectedDay = it })
                }
            }
            if (!calendarMode && entries.isEmpty()) {
                item {
                    Text(
                        "還沒有日記。打開上面的開關，或按右下角自己寫一篇。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (!calendarMode) items(entries, key = { it.id }) { entry ->
                DiaryEntryCard(
                    entry = entry,
                    onEdit = { editing = entry },
                    onDelete = { deleting = entry },
                )
            }
        }
    }

    if (creating || editing != null) {
        DiaryEditorDialog(
            initial = editing,
            initialDate = newEntryDate,
            onDismiss = { creating = false; editing = null; newEntryDate = null },
            onSave = { content, date, visible ->
                val original = editing
                creating = false
                editing = null
                newEntryDate = null
                scope.launch {
                    if (original == null) {
                        DiaryStore.add(
                            context,
                            DiaryEntry(date = date, author = DiaryAuthor.HUMAN, content = content, visibleToAi = visible),
                        )
                    } else {
                        DiaryStore.update(context, original.copy(content = content, date = date, visibleToAi = visible))
                    }
                }
            },
        )
    }

    selectedDay?.let { day ->
        val dayEntries = entries.filter { it.date == day.toString() }.sortedBy { it.createdAt }
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { selectedDay = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    day.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日 EEEE", java.util.Locale.TRADITIONAL_CHINESE)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                val dayMood = moodByDate[day.toString()]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        dayMood?.let { "心情：${it.emoji} ${it.label}" + if (it.note.isNotBlank()) "・${it.note}" else "" } ?: "這天沒有記錄心情",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (!day.isAfter(LocalDate.now())) {
                        TextButton(onClick = { selectedDay = null; moodPicking = day }) { Text(if (dayMood == null) "記錄心情" else "改心情") }
                    }
                }
                if (dayEntries.isEmpty()) {
                    Text("這一天還沒有日記。", style = MaterialTheme.typography.bodyMedium)
                }
                dayEntries.forEach { entry ->
                    DiaryEntryCard(
                        entry = entry,
                        onEdit = { selectedDay = null; editing = entry },
                        onDelete = { deleting = entry },
                    )
                }
                TextButton(onClick = {
                    newEntryDate = day.toString()
                    selectedDay = null
                    creating = true
                }) { Text("寫這一天的日記") }
            }
        }
    }

    moodPicking?.let { day -> MoodPickerDialog(date = day, onDismiss = { moodPicking = null }) }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("刪除這篇日記？") },
            text = { Text("刪除後無法復原。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { DiaryStore.delete(context, entry.id) }
                }) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DiaryEntryCard(entry: DiaryEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    val isAi = entry.author == DiaryAuthor.AI
    val time = remember(entry.createdAt) {
        Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isAi) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.date, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(if (isAi) "他寫的" else "我寫的")
                            append(" · ").append(time)
                            if (!isAi) append(if (entry.visibleToAi) " · 他看得到" else " · 只有我看得到")
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (!isAi) {
                    IconButton(onClick = onEdit) { Icon(HugeIcons.Edit01, contentDescription = "編輯") }
                }
                IconButton(onClick = onDelete) { Icon(HugeIcons.Delete01, contentDescription = "刪除") }
            }
            Text(entry.content, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DiaryEditorDialog(
    initial: DiaryEntry?,
    initialDate: String? = null,
    onDismiss: () -> Unit,
    onSave: (content: String, date: String, visibleToAi: Boolean) -> Unit,
) {
    var content by remember { mutableStateOf(initial?.content.orEmpty()) }
    var date by remember { mutableStateOf(initial?.date ?: initialDate ?: LocalDate.now().toString()) }
    var visible by remember { mutableStateOf(initial?.visibleToAi ?: true) }
    val dateValid = runCatching { LocalDate.parse(date) }.isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "寫日記" else "編輯日記") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it.take(10) },
                    label = { Text("日期（yyyy-MM-dd）") },
                    isError = !dateValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("今天想記下什麼？") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("讓他看得到")
                        Text("關掉的話只有你自己看得到，他翻日記時看不到這篇", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.padding(4.dp))
                    Switch(checked = visible, onCheckedChange = { visible = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank() && dateValid,
                onClick = { onSave(content.trim(), date, visible) },
            ) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private const val CALENDAR_PAGE_COUNT = 2400
private const val CALENDAR_START_PAGE = CALENDAR_PAGE_COUNT / 2

/**
 * 可左右滑动翻页的月历：有日记的日子会标上小点（他写的、我写的颜色不同），点一天看当天所有日记。
 */
@Composable
private fun DiaryCalendar(
    entries: List<DiaryEntry>,
    moods: Map<String, me.rerere.rikkahub.hoshio.together.MoodCheckin>,
    onDayClick: (LocalDate) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = CALENDAR_START_PAGE) { CALENDAR_PAGE_COUNT }
    val thisMonth = remember { java.time.YearMonth.now() }
    val byDate = remember(entries) { entries.groupBy { it.date } }
    val shownMonth = thisMonth.plusMonths((pagerState.currentPage - CALENDAR_START_PAGE).toLong())

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }) { Text("‹ 上個月") }
                Text(
                    "${shownMonth.year} 年 ${shownMonth.monthValue} 月",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }) { Text("下個月 ›") }
            }
            if (shownMonth != thisMonth) {
                TextButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(CALENDAR_START_PAGE) } },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("回到這個月") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                    Text(
                        it,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            androidx.compose.foundation.pager.HorizontalPager(state = pagerState) { page ->
                val month = thisMonth.plusMonths((page - CALENDAR_START_PAGE).toLong())
                MonthGrid(month = month, byDate = byDate, moods = moods, onDayClick = onDayClick)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CalendarDot(MaterialTheme.colorScheme.primary)
                Text("他寫的", style = MaterialTheme.typography.labelSmall)
                CalendarDot(MaterialTheme.colorScheme.tertiary)
                Text("我寫的", style = MaterialTheme.typography.labelSmall)
                Text("😊 心情", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: java.time.YearMonth,
    byDate: Map<String, List<DiaryEntry>>,
    moods: Map<String, me.rerere.rikkahub.hoshio.together.MoodCheckin>,
    onDayClick: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val first = month.atDay(1)
    // 星期日排第一格
    val leading = first.dayOfWeek.value % 7
    val days = month.lengthOfMonth()
    val cells = leading + days
    val rows = (cells + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNumber = row * 7 + col - leading + 1
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.weight(1f).aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (dayNumber in 1..days) {
                            val date = month.atDay(dayNumber)
                            val dayEntries = byDate[date.toString()].orEmpty()
                            val isToday = date == today
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(
                                        when {
                                            isToday -> MaterialTheme.colorScheme.secondaryContainer
                                            dayEntries.isNotEmpty() -> MaterialTheme.colorScheme.surfaceContainerHighest
                                            else -> androidx.compose.ui.graphics.Color.Transparent
                                        }
                                    )
                                    .clickable { onDayClick(date) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                val mood = moods[date.toString()]
                                Text(
                                    "$dayNumber",
                                    style = if (mood != null) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (mood != null) Text(mood.emoji, fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp))
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    if (dayEntries.any { it.author == DiaryAuthor.AI }) CalendarDot(MaterialTheme.colorScheme.primary)
                                    if (dayEntries.any { it.author == DiaryAuthor.HUMAN }) CalendarDot(MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDot(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(6.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color)
    )
}
