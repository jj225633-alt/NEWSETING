package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.hoshio.playroom.PlayroomItem
import me.rerere.rikkahub.hoshio.playroom.PlayroomStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.webview.WEB_VIEW_BASE_URL
import me.rerere.ui.webview.WebView
import me.rerere.ui.webview.WebViewContentCache
import me.rerere.ui.webview.rememberWebViewState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 用全萤幕打开一件作品 */
private suspend fun openFullscreen(context: android.content.Context, navigate: (Screen) -> Unit, id: String) {
    val html = PlayroomStore.readHtml(context, id) ?: return
    val contentId = WebViewContentCache.store(context.cacheDir, html)
    navigate(Screen.WebView(contentId = contentId))
}

/**
 * 对话里显示的作品卡片：可以直接在这里玩，也可以全萤幕打开或去游戏室。
 */
@Composable
fun PlayroomCard(itemId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val items by PlayroomStore.items.collectAsState()
    LaunchedEffect(Unit) { PlayroomStore.load(context) }
    val item = items.firstOrNull { it.id == itemId }
    var playing by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item == null) {
                Text("這件作品已經不在遊戲室了", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🎮 ${item.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (item.description.isNotBlank()) {
                        Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { scope.launch { openFullscreen(context, { navController.navigate(it) }, item.id) } }) {
                    Icon(HugeIcons.Fullscreen, contentDescription = "全螢幕")
                }
            }
            if (playing) {
                val html by produceState<String?>(null, item.id, item.updatedAt) {
                    value = PlayroomStore.readHtml(context, item.id)
                }
                html?.let {
                    val state = rememberWebViewState(data = it, baseUrl = WEB_VIEW_BASE_URL, mimeType = "text/html")
                    WebView(state = state, modifier = Modifier.fillMaxWidth().height(420.dp))
                }
                TextButton(onClick = { playing = false }) { Text("收起") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { playing = true }) {
                        Icon(HugeIcons.Play, contentDescription = null)
                        Text(" 在這裡玩")
                    }
                    TextButton(onClick = { navController.navigate(Screen.Playroom) }) { Text("去遊戲室") }
                }
            }
        }
    }
}

/**
 * 游戏室：他做过的所有小东西。
 */
@Composable
fun PlayroomPage() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val items by PlayroomStore.items.collectAsState()
    var deleting by remember { mutableStateOf<PlayroomItem?>(null) }
    LaunchedEffect(Unit) { PlayroomStore.load(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("遊戲室") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    Text(
                        "這裡還空空的。跟他說「做一個小遊戲給我玩」，或「幫我做一張可愛的卡片」，他做好的東西都會放在這裡。" +
                            "（要先在他的本地工具打開「遊戲室」）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(items, key = { it.id }) { item ->
                Card(
                    onClick = { scope.launch { openFullscreen(context, { navController.navigate(it) }, item.id) } },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                (if (item.pinned) "📌 " else "") + item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (item.description.isNotBlank()) {
                                Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                Instant.ofEpochMilli(item.updatedAt).atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        IconButton(onClick = { scope.launch { PlayroomStore.setPinned(context, item.id, !item.pinned) } }) {
                            Icon(if (item.pinned) HugeIcons.PinOff else HugeIcons.Pin, contentDescription = "釘選")
                        }
                        IconButton(onClick = { deleting = item }) {
                            Icon(HugeIcons.Delete01, contentDescription = "刪除")
                        }
                    }
                }
            }
        }
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("刪除「${item.title}」？") },
            text = { Text("刪除後對話裡的卡片也會打不開。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { PlayroomStore.delete(context, item.id) }
                }) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}
