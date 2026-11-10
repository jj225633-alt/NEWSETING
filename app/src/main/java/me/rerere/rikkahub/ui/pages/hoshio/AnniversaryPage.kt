package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.hoshio.anniversary.Anniversary
import me.rerere.rikkahub.hoshio.anniversary.AnniversaryStore
import me.rerere.rikkahub.hoshio.care.CareWorker
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 纪念日：认识第几天、重要的日子（她或他都能新增），当天他会来提醒。
 */
@Composable
fun AnniversaryPage() {
    val context = LocalContext.current
    val items by AnniversaryStore.items.collectAsState()
    remember { AnniversaryStore.load(context) }
    var metDate by remember { mutableStateOf(AnniversaryStore.metDate(context)) }
    var remind by remember { mutableStateOf(AnniversaryStore.remindEnabled(context)) }
    var adding by remember { mutableStateOf(false) }
    var editingMet by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Anniversary?>(null) }
    val today = LocalDate.now()
    val upcoming = remember(items) { items.mapNotNull { a -> a.nextOccurrence(today)?.let { a to it } }.sortedBy { it.second } }
    val past = remember(items) { items.filter { it.nextOccurrence(today) == null } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("紀念日") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors)
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(HugeIcons.Add01, null) }, text = { Text("新增日子") })
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    onClick = { editingMet = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val met = metDate
                        if (met != null) {
                            Text("認識的第 ${ChronoUnit.DAYS.between(met, today) + 1} 天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("從 $met 開始・點這裡修改", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("你們是哪一天認識的？", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("點這裡設定，首頁就會顯示認識第幾天", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("當天讓他來找你", style = MaterialTheme.typography.titleMedium)
                            Text("重要日子的早上 8 點後，他會主動傳訊息一起記得這一天", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = remind, onCheckedChange = {
                            AnniversaryStore.setRemindEnabled(context, it)
                            remind = it
                            CareWorker.sync(context)
                        })
                    }
                }
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        "還沒有重要的日子。按右下角新增，或直接跟他說「我的生日是 X 月 X 日」，他會幫你記下來。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(upcoming, key = { it.first.id }) { (item, next) ->
                AnniversaryCard(item, next, today) { deleting = item }
            }
            items(past, key = { it.id }) { item ->
                AnniversaryCard(item, null, today) { deleting = item }
            }
        }
    }

    if (adding) {
        var title by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(today.toString()) }
        var yearly by remember { mutableStateOf(true) }
        val valid = title.isNotBlank() && runCatching { LocalDate.parse(date) }.isSuccess
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("新增重要的日子") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it.take(30) }, label = { Text("名稱，例如：我的生日") }, singleLine = true)
                    OutlinedTextField(value = date, onValueChange = { date = it.take(10) }, label = { Text("日期（yyyy-MM-dd）") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("每年都算", Modifier.weight(1f))
                        Switch(checked = yearly, onCheckedChange = { yearly = it })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    AnniversaryStore.add(context, Anniversary(title = title.trim(), date = date, yearly = yearly))
                    CareWorker.sync(context)
                    adding = false
                }) { Text("新增") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
    }

    if (editingMet) {
        var date by remember { mutableStateOf((metDate ?: today).toString()) }
        AlertDialog(
            onDismissRequest = { editingMet = false },
            title = { Text("認識的日子") },
            text = { OutlinedTextField(value = date, onValueChange = { date = it.take(10) }, label = { Text("yyyy-MM-dd") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = runCatching { LocalDate.parse(date) }.isSuccess, onClick = {
                    val d = LocalDate.parse(date)
                    AnniversaryStore.setMetDate(context, d)
                    metDate = d
                    editingMet = false
                }) { Text("儲存") }
            },
            dismissButton = { TextButton(onClick = { editingMet = false }) { Text("取消") } },
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("刪除「${item.title}」？") },
            confirmButton = { TextButton(onClick = { AnniversaryStore.remove(context, item.id); deleting = null }) { Text("刪除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun AnniversaryCard(item: Anniversary, next: LocalDate?, today: LocalDate, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(item.date)
                        if (item.yearly) append("・每年")
                        if (item.addedBy == "ai") append("・他記下的")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                when {
                    next == null -> "已過去"
                    next == today -> "就是今天 🎉"
                    else -> "還有 ${ChronoUnit.DAYS.between(today, next)} 天"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onDelete) { Icon(HugeIcons.Delete01, contentDescription = "刪除") }
        }
    }
}
