package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
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

    LaunchedEffect(Unit) { DiaryStore.load(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日記") },
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
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(20, 21, 22, 23).forEach { hour ->
                                    androidx.compose.material3.FilterChip(
                                        selected = diaryHour == hour,
                                        onClick = { diaryHour = hour; prefs.diaryWriteHour = hour },
                                        label = { Text("$hour:00 後寫") },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = {
                            CareWorker.writeDiaryNow(context)
                            toaster.show(message = "請他現在寫今天的日記，寫好會通知你", type = ToastType.Success)
                        }) { Text("現在寫今天的日記") }
                    }
                }
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        "還沒有日記。打開上面的開關，或按右下角自己寫一篇。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(entries, key = { it.id }) { entry ->
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
            onDismiss = { creating = false; editing = null },
            onSave = { content, date, visible ->
                val original = editing
                creating = false
                editing = null
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
    onDismiss: () -> Unit,
    onSave: (content: String, date: String, visibleToAi: Boolean) -> Unit,
) {
    var content by remember { mutableStateOf(initial?.content.orEmpty()) }
    var date by remember { mutableStateOf(initial?.date ?: LocalDate.now().toString()) }
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
