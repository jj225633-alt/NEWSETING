package me.rerere.rikkahub.ui.pages.hoshio

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.hoshio.care.CareWorker
import me.rerere.rikkahub.hoshio.together.AlbumPhoto
import me.rerere.rikkahub.hoshio.together.AlbumStore
import me.rerere.rikkahub.hoshio.together.BY_HER
import me.rerere.rikkahub.hoshio.together.BY_HIM
import me.rerere.rikkahub.hoshio.together.Capsule
import me.rerere.rikkahub.hoshio.together.CapsuleStore
import me.rerere.rikkahub.hoshio.together.MoodCheckinStore
import me.rerere.rikkahub.hoshio.together.OurPromise
import me.rerere.rikkahub.hoshio.together.PromiseStore
import me.rerere.rikkahub.hoshio.together.Routine
import me.rerere.rikkahub.hoshio.together.RoutineLogStore
import me.rerere.rikkahub.hoshio.together.RoutineStore
import me.rerere.rikkahub.hoshio.together.SleepMode
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val T_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private fun Long.tFmt() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(T_FMT)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SoftCard(highlight: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = CardDefaults.cardColors(
        containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    if (onClick != null) {
        Card(onClick = onClick, colors = colors, shape = MaterialTheme.shapes.extraLarge) { Box(Modifier.padding(16.dp).fillMaxWidth()) { content() } }
    } else {
        Card(colors = colors, shape = MaterialTheme.shapes.extraLarge) { Box(Modifier.padding(16.dp).fillMaxWidth()) { content() } }
    }
}

// ───────────────────────── 生活照顾 ─────────────────────────

private val ROUTINE_PRESETS = listOf(
    Routine(kind = "water", label = "喝水", times = listOf("10:00", "14:00", "17:00")),
    Routine(kind = "meal", label = "吃飯", times = listOf("12:00", "18:30")),
    Routine(kind = "medicine", label = "吃藥", times = listOf("09:00")),
)

@Composable
fun RoutinePage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val routines by RoutineStore.items.collectAsState()
    val logs by RoutineLogStore.items.collectAsState()
    remember { RoutineStore.load(context); RoutineLogStore.load(context) }
    var editing by remember { mutableStateOf<Routine?>(null) }
    val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    PlayScaffold("生活照顧") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "設定喝水、吃飯、吃藥的時間。時間到了你還沒按「做了」，他會來提醒你。\n" +
                        "也可以直接跟他說，例如「接下來三天早中晚提醒我吃藥」，他會幫你設好；跟他說「吃過了」他也會幫你記。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(routines, key = { it.id }) { r ->
                val doneToday = logs.filter { it.routineId == r.id && it.at >= startOfDay }
                SoftCard(highlight = r.active, onClick = { editing = r }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${r.emoji} ${r.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                r.times.joinToString("、") + (r.until?.let { "・到 ${it.substring(5).replace('-', '/')}" } ?: "・每天") +
                                    when {
                                        !r.enabled -> "・已暫停"
                                        r.ended() -> "・已結束"
                                        else -> ""
                                    },
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                if (doneToday.isEmpty()) "今天還沒做" else "今天：" + doneToday.joinToString("、") { l ->
                                    l.at.tFmt().substringAfter(' ') + if (l.by == BY_HIM) "（他記的）" else ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        FilledTonalButton(onClick = {
                            RoutineLogStore.log(context, r.id, BY_HER)
                            toaster.show(message = "記下來了 ✓", type = ToastType.Success)
                        }) { Text("做了") }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("新增")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ROUTINE_PRESETS.forEach { preset ->
                        FilledTonalButton(onClick = { editing = preset.copy(id = Routine(kind = "x", label = "", times = emptyList()).id) }) {
                            Text("${preset.emoji} ${preset.label}")
                        }
                    }
                    FilledTonalButton(onClick = { editing = Routine(kind = "custom", label = "", times = listOf("21:00")) }) { Text("⏰ 自訂") }
                }
                }
            }
        }
    }

    editing?.let { r ->
        val exists = routines.any { it.id == r.id }
        var label by remember(r.id) { mutableStateOf(r.label) }
        var times by remember(r.id) { mutableStateOf(r.times.joinToString(" ")) }
        var enabled by remember(r.id) { mutableStateOf(r.enabled) }
        var days by remember(r.id) {
            mutableStateOf(r.until?.let { runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(it)).toInt() + 1 }.getOrNull() } ?: 0)
        }
        val parsed = times.split(' ', ',', '、', '，').mapNotNull { t ->
            runCatching { java.time.LocalTime.parse(t.trim().padStart(5, '0')).toString() }.getOrNull()
        }.distinct().sorted()
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (exists) "編輯" else "新增") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("名稱") }, singleLine = true)
                    OutlinedTextField(value = times, onValueChange = { times = it }, label = { Text("時間（用空格分開，例如 10:00 14:00）") })
                    Text("會在：${parsed.joinToString("、").ifEmpty { "（時間格式不對）" }}", style = MaterialTheme.typography.bodySmall)
                    Text("持續多久", style = MaterialTheme.typography.labelLarge)
                    NumberChoiceChips(
                        options = listOf(0, 1, 3, 5, 7, 14),
                        selected = days,
                        label = { d -> if (d == 0) "每天" else if (d == 1) "只有今天" else "$d 天" },
                        range = 0..365,
                        unit = "天（0 = 每天）",
                    ) { days = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("開啟提醒", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = label.isNotBlank() && parsed.isNotEmpty(), onClick = {
                    val updated = r.copy(
                        label = label.trim(),
                        times = parsed,
                        enabled = enabled,
                        until = if (days > 0) LocalDate.now().plusDays(days - 1L).toString() else null,
                    )
                    RoutineStore.update(context) { list -> if (exists) list.map { if (it.id == r.id) updated else it } else list + updated }
                    CareWorker.sync(context)
                    editing = null
                }) { Text("儲存") }
            },
            dismissButton = {
                Row {
                    if (exists) TextButton(onClick = { RoutineStore.update(context) { it.filterNot { x -> x.id == r.id } }; editing = null }) { Text("刪除") }
                    TextButton(onClick = { editing = null }) { Text("取消") }
                }
            },
        )
    }
}

// ───────────────────────── 心情月历 ─────────────────────────

@Composable
fun MoodCalendarPage() {
    val context = LocalContext.current
    val checkins by MoodCheckinStore.items.collectAsState()
    remember { MoodCheckinStore.load(context) }
    val callHim = rememberCallHim()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var picking by remember { mutableStateOf<LocalDate?>(null) }
    val byDate = checkins.associateBy { it.date }
    val today = LocalDate.now()

    PlayScaffold("心情月曆") { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val todayCheckin = byDate[today.toString()]
            SoftCard(highlight = true, onClick = { picking = today }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("今天的心情", style = MaterialTheme.typography.labelLarge)
                    if (todayCheckin == null) {
                        Text("點這裡打卡 ✨", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("${todayCheckin.emoji} ${todayCheckin.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (todayCheckin.note.isNotBlank()) Text(todayCheckin.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                Text("${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Text("›") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                    Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                }
            }
            val offset = month.atDay(1).dayOfWeek.value % 7
            val cells = List(offset) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0 until 7).forEach { i ->
                        val date = week.getOrNull(i)
                        val c = date?.let { byDate[it.toString()] }
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(MaterialTheme.shapes.medium)
                                .background(
                                    when {
                                        date == null -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                        date == today -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                    }
                                )
                                .clickable(enabled = date != null && !date.isAfter(today)) { picking = date },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (date != null) {
                                if (c != null) Text(c.emoji, fontSize = 20.sp) else Text("${date.dayOfMonth}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            val monthList = checkins.filter { it.date.startsWith(month.toString()) }
            if (monthList.isNotEmpty()) {
                val counts = monthList.groupingBy { it.emoji + it.label }.eachCount().entries.sortedByDescending { it.value }
                Text("這個月：" + counts.joinToString("、") { "${it.key} ${it.value} 天" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    picking?.let { date -> MoodPickerDialog(date = date, onDismiss = { picking = null }) }
}

/** 记录某一天的心情；今天的可以选要不要让他知道 */
@Composable
internal fun MoodPickerDialog(date: LocalDate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val checkins by MoodCheckinStore.items.collectAsState()
    remember { MoodCheckinStore.load(context) }
    val callHim = rememberCallHim()
    val today = LocalDate.now()
    val existing = checkins.firstOrNull { it.date == date.toString() }
    var selected by remember(date) { mutableStateOf(existing?.let { it.emoji to it.label }) }
    var note by remember(date) { mutableStateOf(existing?.note.orEmpty()) }
    var tell by remember(date) { mutableStateOf(date == today) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (date == today) "今天的心情" else "${date.monthValue}/${date.dayOfMonth} 的心情") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MoodCheckinStore.MOODS.forEach { m ->
                        FilterChip(selected = selected == m, onClick = { selected = m }, label = { Text("${m.first} ${m.second}") })
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("想說點什麼（可不填）") }, minLines = 2)
                if (date == today) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("讓他知道", modifier = Modifier.weight(1f))
                        Switch(checked = tell, onCheckedChange = { tell = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = {
                val (emoji, label) = selected!!
                MoodCheckinStore.checkIn(context, emoji, label, note, date)
                onDismiss()
                if (tell && date == today) {
                    callHim("心情打卡", "She just checked in her mood for today: $emoji $label" +
                        (if (note.isNotBlank()) " — \"$note\"" else "") + ". Respond to her in character.")
                }
            }) { Text("打卡") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ───────────────────────── 回忆相簿 ─────────────────────────


@Composable
fun AlbumPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val photos by AlbumStore.items.collectAsState()
    remember { AlbumStore.load(context) }
    val callHim = rememberCallHim()
    var viewing by remember { mutableStateOf<AlbumPhoto?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val names = withContext(Dispatchers.IO) { uris.mapNotNull { AlbumStore.importImage(context, it) } }
            AlbumStore.update(context) { list -> names.map { AlbumPhoto(fileName = it) } + list }
            toaster.show(message = "加了 ${names.size} 張", type = ToastType.Success)
        }
    }
    val uncaptioned = photos.count { it.hisCaption.isBlank() }

    PlayScaffold("回憶相簿") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("你可以在這裡加照片；也可以在聊天時傳照片給他，說「把這張放進相簿」，他會自己存進來並寫一句話。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("加照片") }
                    if (uncaptioned > 0) {
                        FilledTonalButton(onClick = {
                            callHim("回憶相簿", "She added photos to your memory album. Call album list, then view each photo without your caption " +
                                "and write a one-line caption for it with album caption. Then tell her briefly what you thought.")
                        }) { Text("請他寫 $uncaptioned 張的心得") }
                    }
                }
            }
            if (photos.isEmpty()) {
                item { Text("相簿還是空的。把想留下來的照片放進來，他會替每張寫一句話。", style = MaterialTheme.typography.bodyMedium) }
            }
            items(photos, key = { it.id }) { p ->
                Card(onClick = { viewing = p }, shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column {
                        AsyncImage(
                            model = Uri.fromFile(AlbumStore.file(context, p)).toString(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                        )
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (p.hisCaption.isNotBlank()) Text("「${p.hisCaption}」", style = MaterialTheme.typography.bodyLarge)
                            if (p.herCaption.isNotBlank()) Text("你：${p.herCaption}", style = MaterialTheme.typography.bodyMedium)
                            Text(p.at.tFmt(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    viewing?.let { p ->
        var caption by remember(p.id) { mutableStateOf(p.herCaption) }
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text("照片") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(model = Uri.fromFile(AlbumStore.file(context, p)).toString(), contentDescription = null, modifier = Modifier.fillMaxWidth())
                    if (p.hisCaption.isNotBlank()) Text("他：${p.hisCaption}")
                    OutlinedTextField(value = caption, onValueChange = { caption = it }, label = { Text("你想寫的話") })
                }
            },
            confirmButton = { TextButton(onClick = { AlbumStore.setCaption(context, p.id, her = caption); viewing = null }) { Text("儲存") } },
            dismissButton = { TextButton(onClick = { AlbumStore.delete(context, p.id); viewing = null }) { Text("刪除") } },
        )
    }
}

// ───────────────────────── 时光胶囊 ─────────────────────────

@Composable
fun CapsulePage() {
    val context = LocalContext.current
    val capsules by CapsuleStore.items.collectAsState()
    remember { CapsuleStore.load(context) }
    val callHim = rememberCallHim()
    var writing by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<Capsule?>(null) }

    PlayScaffold("時光膠囊") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("寫給未來的信，封好之後到了那天才能打開。你寫的他也看不到，直到你打開。", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { writing = true }) { Text("寫一顆膠囊") }
                    FilledTonalButton(onClick = {
                        callHim("時光膠囊", "She would like you to write her a time capsule. Think about when it should open (a meaningful future date) " +
                            "and seal it with time_capsule write. Do not tell her what is inside, just that it is sealed and when it opens.")
                    }) { Text("請他寫一顆給我") }
                }
            }
            items(capsules, key = { it.id }) { c ->
                val unlocked = c.unlocked()
                SoftCard(highlight = unlocked && !c.opened, onClick = {
                    if (unlocked) {
                        if (!c.opened) CapsuleStore.update(context) { list -> list.map { if (it.id == c.id) it.copy(opened = true) else it } }
                        reading = c
                    }
                }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            (if (!unlocked) "🔒 " else if (c.opened) "📖 " else "🎁 ") + c.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            (if (c.by == BY_HIM) "他寫的" else "你寫的") + "・" +
                                (if (unlocked) (if (c.opened) "已打開" else "可以打開了！") else "${c.openOn} 才能打開"),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }

    if (writing) {
        var title by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(LocalDate.now().plusMonths(1).toString()) }
        val parsed = runCatching { LocalDate.parse(date.trim()) }.getOrNull()?.takeIf { it.isAfter(LocalDate.now()) }
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text("寫給未來") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("標題") }, singleLine = true)
                    OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("內容") }, minLines = 5)
                    OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("哪天打開（yyyy-MM-dd）") }, singleLine = true, isError = parsed == null)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("一個月後" to 1L, "半年後" to 6L, "一年後" to 12L).forEach { (label, months) ->
                            FilterChip(selected = false, onClick = { date = LocalDate.now().plusMonths(months).toString() }, label = { Text(label) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = parsed != null && content.isNotBlank(), onClick = {
                    CapsuleStore.update(context) { listOf(Capsule(by = BY_HER, title = title.ifBlank { "給未來的我們" }.take(40), content = content, openOn = parsed.toString())) + it }
                    CareWorker.sync(context)
                    writing = false
                }) { Text("封起來") }
            },
            dismissButton = { TextButton(onClick = { writing = false }) { Text("取消") } },
        )
    }
    reading?.let { c ->
        AlertDialog(
            onDismissRequest = { reading = null },
            title = { Text(c.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text((if (c.by == BY_HIM) "他" else "你") + " 在 ${c.createdAt.tFmt()} 封起來的", style = MaterialTheme.typography.labelSmall)
                    Text(c.content, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    reading = null
                    callHim("時光膠囊", "She just opened a time capsule (\"${c.title}\", written by ${if (c.by == BY_HIM) "you" else "her"} " +
                        "on ${c.createdAt.tFmt()}). Its content: \"${c.content.take(3000)}\". Talk with her about it in character.")
                }) { Text("跟他聊聊") }
            },
            dismissButton = { TextButton(onClick = { reading = null }) { Text("收好") } },
        )
    }
}

// ───────────────────────── 我们的约定 ─────────────────────────

@Composable
fun PromisePage() {
    val context = LocalContext.current
    val promises by PromiseStore.items.collectAsState()
    remember { PromiseStore.load(context) }
    var adding by remember { mutableStateOf(false) }

    PlayScaffold("我們的約定") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("你們說好的事。聊天時說到「下次一起…」「我答應你…」他也會自己記下來，有日期的到了那天會提起。", style = MaterialTheme.typography.bodyMedium)
            }
            item { Button(onClick = { adding = true }) { Text("新增約定") } }
            val (open, done) = promises.partition { !it.done }
            if (open.isNotEmpty()) item { SectionTitle("還沒完成") }
            items(open, key = { it.id }) { PromiseRow(it) }
            if (done.isNotEmpty()) item { SectionTitle("已經做到的 ✨") }
            items(done, key = { it.id }) { PromiseRow(it) }
        }
    }

    if (adding) {
        var text by remember { mutableStateOf("") }
        var due by remember { mutableStateOf("") }
        var by by remember { mutableStateOf("both") }
        val parsedDue = due.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("新增約定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("約定") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(BY_HER to "我答應的", BY_HIM to "他答應的", "both" to "一起").forEach { (v, l) ->
                            FilterChip(selected = by == v, onClick = { by = v }, label = { Text(l) })
                        }
                    }
                    OutlinedTextField(
                        value = due,
                        onValueChange = { due = it },
                        label = { Text("日期（可不填，yyyy-MM-dd）") },
                        singleLine = true,
                        isError = due.isNotBlank() && parsedDue == null,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() && (due.isBlank() || parsedDue != null), onClick = {
                    PromiseStore.update(context) { listOf(OurPromise(text = text.trim().take(100), by = by, due = parsedDue)) + it }
                    CareWorker.sync(context)
                    adding = false
                }) { Text("約好了") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PromiseRow(p: OurPromise) {
    val context = LocalContext.current
    var deleting by remember { mutableStateOf(false) }
    SoftCard(highlight = !p.done) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = p.done, onCheckedChange = { checked ->
                PromiseStore.update(context) { list -> list.map { if (it.id == p.id) it.copy(done = checked, doneAt = if (checked) System.currentTimeMillis() else null) else it } }
            })
            Column(Modifier.weight(1f).clickable { deleting = true }) {
                Text(p.text, style = MaterialTheme.typography.bodyLarge)
                Text(
                    when (p.by) { BY_HER -> "你答應的"; BY_HIM -> "他答應的"; else -> "一起的" } +
                        (p.due?.let { "・$it" } ?: "") + (p.doneAt?.let { "・${it.tFmt()} 完成" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("刪除這個約定？") },
            text = { Text(p.text) },
            confirmButton = { TextButton(onClick = { PromiseStore.update(context) { it.filterNot { x -> x.id == p.id } }; deleting = false }) { Text("刪除") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } },
        )
    }
}

// ───────────────────────── 晚安模式 ─────────────────────────

@Composable
fun SleepPage() {
    val context = LocalContext.current
    val callHim = rememberCallHim()
    var sleeping by remember { mutableStateOf(SleepMode.isOn(context)) }
    var story by remember { mutableStateOf(true) }

    PlayScaffold("晚安") { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (sleeping) "🌙" else "🌛", fontSize = 80.sp)
            if (sleeping) {
                Text("晚安模式中", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "他會安靜地陪著你，不主動吵你；早上偵測到你起床會來說早安（要開「起床問候」），最晚明天 10 點自動結束。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = {
                    SleepMode.stop(context)
                    sleeping = false
                }) { Text("我醒了") }
            } else {
                Text("要睡了嗎？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("聽他講個睡前故事")
                    Switch(checked = story, onCheckedChange = { story = it })
                }
                Button(onClick = {
                    SleepMode.start(context)
                    sleeping = true
                    callHim(
                        "晚安",
                        "She is going to sleep now and turned on sleep mode (you will not be woken for proactive care until she wakes up). " +
                            if (story) {
                                "Tell her a short, soft original bedtime story (about 150-250 characters, calm and warm, can feature you two or a small fantasy), then say goodnight. No questions at the end."
                            } else {
                                "Say a short, warm goodnight. No questions at the end."
                            },
                    )
                }) { Text("晚安") }
            }
        }
    }
}
