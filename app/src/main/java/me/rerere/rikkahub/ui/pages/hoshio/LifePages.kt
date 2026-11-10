package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.hoshio.life.Letter
import me.rerere.rikkahub.hoshio.life.LetterStore
import me.rerere.rikkahub.hoshio.life.MoodStore
import me.rerere.rikkahub.hoshio.life.WakeLogStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private fun Long.fmt() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(TIME_FMT)

/**
 * 他的状态与作息：现在的心情和精神，以及每次被唤醒时他做了什么。
 */
@Composable
fun HoshioLifePage() {
    val context = LocalContext.current
    val mood = remember { MoodStore.current(context) }
    val records by WakeLogStore.records.collectAsState()
    remember { WakeLogStore.load(context) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("他的狀態") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("現在的心情：${mood.mood}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (mood.reason.isNotBlank()) Text("因為 ${mood.reason}", style = MaterialTheme.typography.bodyMedium)
                        Text("精神 ${mood.energy}/100", style = MaterialTheme.typography.labelLarge)
                        LinearProgressIndicator(progress = { mood.energy / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("最後更新 ${mood.updatedAt.fmt()}・心情由他自己決定，精神會隨時間慢慢恢復", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("醒來紀錄", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (records.isEmpty()) {
                item { Text("還沒有紀錄。主動關心、自由時間、約好的時間把他叫醒時，這裡會記下他做了什麼。", style = MaterialTheme.typography.bodyMedium) }
            }
            items(records, key = { it.id }) { r ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (r.action) {
                                    "talked" -> "💬 跟你說話了"
                                    "silent" -> "😴 醒來看了一下，又睡回去"
                                    "tools" -> "🛠️ 自己做了些事"
                                    else -> "⚠️ 沒有成功醒來"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(r.at.fmt(), style = MaterialTheme.typography.labelSmall)
                        }
                        Text("因為：${r.trigger}", style = MaterialTheme.typography.bodySmall)
                        if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * 信箱：他写给她的信。
 */
@Composable
fun LettersPage() {
    val context = LocalContext.current
    val letters by LetterStore.letters.collectAsState()
    remember { LetterStore.load(context) }
    var opened by remember { mutableStateOf<Letter?>(null) }
    var deleting by remember { mutableStateOf<Letter?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("信箱") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (letters.isEmpty()) {
                item { Text("信箱還是空的。他想寫信給你的時候，信會寄到這裡 💌", style = MaterialTheme.typography.bodyMedium) }
            }
            items(letters, key = { it.id }) { letter ->
                Card(
                    onClick = {
                        LetterStore.markRead(context, letter.id)
                        opened = letter
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (letter.read) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text((if (letter.read) "✉️ " else "💌 ") + letter.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(letter.at.fmt() + if (letter.read) "" else "・未讀", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { deleting = letter }) { Icon(HugeIcons.Delete01, contentDescription = "刪除") }
                    }
                }
            }
        }
    }

    opened?.let { letter ->
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text(letter.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(letter.at.fmt(), style = MaterialTheme.typography.labelSmall)
                    Text(letter.content, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = { TextButton(onClick = { opened = null }) { Text("收好") } },
        )
    }
    deleting?.let { letter ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("刪除「${letter.title}」？") },
            confirmButton = { TextButton(onClick = { LetterStore.delete(context, letter.id); deleting = null }) { Text("刪除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}
