package me.rerere.rikkahub.ui.pages.hoshio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.hoshio.together.AlbumStore
import me.rerere.rikkahub.hoshio.together.BY_HER
import me.rerere.rikkahub.hoshio.together.BY_HIM
import me.rerere.rikkahub.hoshio.together.BucketDone
import me.rerere.rikkahub.hoshio.together.BucketItem
import me.rerere.rikkahub.hoshio.together.BucketList
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val BUCKET_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/**
 * 星汐「我們的 100 件小事」：清單 + 拍立得照片牆。
 */
@Composable
fun BucketListPage() {
    val context = LocalContext.current
    val state by BucketList.state.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var completing by remember { mutableStateOf<BucketItem?>(null) }
    var viewing by remember { mutableStateOf<Pair<BucketItem, BucketDone>?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { BucketList.load(context) }

    val doneMap = state.done.associateBy { it.itemId }
    val shown = when (filter) {
        "todo" -> state.items.filter { it.id !in doneMap }
        "done" -> state.items.filter { it.id in doneMap }.sortedByDescending { doneMap[it.id]?.at }
        else -> state.items
    }

    PlayScaffold("我們的 100 件小事") { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("OUR LITTLE ADVENTURES · ${doneMap.size} / ${state.items.size}", style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
                    LinearProgressIndicator(
                        progress = { if (state.items.isEmpty()) 0f else doneMap.size.toFloat() / state.items.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("全部") })
                        FilterChip(selected = filter == "todo", onClick = { filter = "todo" }, label = { Text("還沒做") })
                        FilterChip(selected = filter == "done", onClick = { filter = "done" }, label = { Text("照片牆") })
                        TextButton(onClick = { adding = true }) { Text("＋ 加一件") }
                    }
                }
            }
            items(shown, key = { it.id }) { item ->
                val done = doneMap[item.id]
                val number = BucketList.number(state, item.id)
                if (done != null) {
                    Polaroid(number, item, done, onClick = { viewing = item to done })
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.82f).clickable { completing = item },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(12.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                            Text("%02d".format(number), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif, color = MaterialTheme.colorScheme.primary)
                            Text(item.text, style = MaterialTheme.typography.bodyMedium)
                            Text(item.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    completing?.let { item -> CompleteDialog(item, onDismiss = { completing = null }) }
    viewing?.let { (item, done) ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text("第 ${BucketList.number(state, item.id)} 件：${item.text}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Instant.ofEpochMilli(done.at).atZone(ZoneId.systemDefault()).format(BUCKET_DATE) + if (done.by == BY_HIM) "・他打的勾" else "", style = MaterialTheme.typography.labelMedium)
                    if (done.note.isNotBlank()) Text("你：${done.note}")
                    Text(if (done.caption.isNotBlank()) "他：${done.caption}" else "他還沒寫話", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askCaption(item, done, BucketList.number(state, item.id))
                    viewing = null
                }) { Text(if (done.caption.isBlank()) "請他寫一句" else "請他重寫") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { BucketList.undo(context, item.id); viewing = null }) { Text("取消打卡") }
                    if (item.custom) TextButton(onClick = { BucketList.remove(context, item.id); viewing = null }) { Text("刪掉這件") }
                }
            },
        )
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("加一件我們想做的事") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(80) }, placeholder = { Text("例如：一起看一次流星雨") }) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { BucketList.add(context, text); adding = false }) { Text("加入") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
    }
}

private val askScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun askCaption(item: BucketItem, done: BucketDone, number: Int) {
    askScope.launch {
        runCatching {
            me.rerere.rikkahub.hoshio.HoshioChat.post(
                "📷 100 件小事 · 第 $number 件",
                "Automatic event (not typed by her): she just checked off item #$number of your shared list \"我們的 100 件小事\": \"${item.text}\"" +
                    (if (done.photo != null) " with a polaroid photo" else "") + (if (done.note.isNotBlank()) ". Her note: \"${done.note}\"" else "") +
                    ". Write ONE short line for the bottom of the polaroid with bucket_list action=caption id=${item.id}, then say a little something to her about it.",
            )
        }
    }
}

@Composable
private fun Polaroid(number: Int, item: BucketItem, done: BucketDone, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxWidth().background(Color(0xFFFBF8F2), RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFFE9E3D8))) {
                val file = done.photo?.let { File(AlbumStore.dir(context), it) }
                if (file != null && file.exists()) {
                    coil3.compose.AsyncImage(model = file, contentDescription = item.text, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("✓", fontSize = 40.sp, color = Color(0xFFB79B6E), modifier = Modifier.align(Alignment.Center))
                }
            }
            Text("%02d · ".format(number) + item.text, style = MaterialTheme.typography.labelMedium, color = Color(0xFF3A3530), fontWeight = FontWeight.Medium, maxLines = 2)
            Text(done.caption.ifBlank { Instant.ofEpochMilli(done.at).atZone(ZoneId.systemDefault()).format(BUCKET_DATE) },
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Serif, color = Color(0xFF7A6E60), maxLines = 2)
        }
    }
}

@Composable
private fun CompleteDialog(item: BucketItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var photo by remember { mutableStateOf<android.net.Uri?>(null) }
    var note by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { photo = it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("做到了嗎？選一張照片做成拍立得（也可以不放照片），他會在下面寫一句話。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (photo == null) "📷 選照片" else "✓ 已選照片（點一下換）")
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, placeholder = { Text("想記下的話（可空白）") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = photo
                val n = note
                askScope.launch {
                    val done = BucketList.complete(context, item.id, BY_HER, p, n) ?: return@launch
                    askCaption(item, done, BucketList.number(BucketList.load(context), item.id))
                }
                onDismiss()
            }) { Text("完成打卡") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("還沒") } },
    )
}
