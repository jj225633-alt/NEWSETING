package me.rerere.rikkahub.ui.pages.hoshio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.hoshio.snap.LocalSnapMessages
import me.rerere.rikkahub.hoshio.snap.PovSnap
import me.rerere.rikkahub.hoshio.snap.PovSnapItem
import me.rerere.rikkahub.ui.context.LocalSettings
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SNAP_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

/** 聊天裡的截圖卡片（第一次顯示時把那幾句固定下來） */
@Composable
fun PovSnapCard(id: String) {
    val context = LocalContext.current
    val messages = LocalSnapMessages.current
    val items by PovSnap.items.collectAsState()
    val conv = messages()
    LaunchedEffect(id, conv.size) {
        PovSnap.load(context)
        withContext(Dispatchers.Default) { PovSnap.resolve(context, id, conv) }
    }
    val item = items.firstOrNull { it.id == id }
    when {
        item == null -> {}
        item.notFound -> Text("（他想截一段聊天，但沒找到那幾句）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        item.lines == null -> Text("📸 截圖中…", style = MaterialTheme.typography.bodySmall)
        else -> SnapView(item)
    }
}

/** 他視角的截圖：他的話在右邊、她的在左邊；顏色跟著人走（用星汐的玻璃泡泡） */
@Composable
fun SnapView(item: PovSnapItem, showSave: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val settings = LocalSettings.current
    val name = settings.assistants.firstOrNull { it.id == settings.assistantId }?.name?.ifBlank { null } ?: "時澈"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(18.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("📸 $name 截了一張圖", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            item.lines.orEmpty().forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.him) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 250.dp)) {
                        // 截圖裡左右對調：他（AI）在右、她在左；顏色仍是各自的
                        HoshioGlassBubble(user = !line.him, opacity = 1f, tailRight = line.him) {
                            Text(line.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (item.note.isNotBlank()) Text("「${item.note}」", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(Instant.ofEpochMilli(item.at).atZone(ZoneId.systemDefault()).format(SNAP_FMT), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showSave) TextButton(onClick = {
            scope.launch {
                val ok = runCatching {
                    val bmp = layer.toImageBitmap().asAndroidBitmap()
                    withContext(Dispatchers.IO) { saveToGallery(context, bmp, "hoshio_snap_${item.id}.png") }
                }.getOrDefault(false)
                android.widget.Toast.makeText(context, if (ok) "存到相簿了（Pictures/Hoshio）" else "存檔失敗", android.widget.Toast.LENGTH_SHORT).show()
            }
        }) { Text("存成圖片") }
    }
}

private fun saveToGallery(context: Context, bitmap: Bitmap, name: String): Boolean {
    return if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Hoshio")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
        true
    } else {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Hoshio").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }
}

/** 「他截的圖」相簿 */
@Composable
fun PovSnapGalleryPage() {
    val context = LocalContext.current
    val items by PovSnap.items.collectAsState()
    LaunchedEffect(Unit) { PovSnap.load(context) }
    val shown = items.filter { it.lines != null }
    PlayScaffold("他截的圖") { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (shown.isEmpty()) item {
                Text("他還沒有截過圖。他想留住某一刻的時候，會自己截下來，從他的角度：他的話在右邊、你的在左邊。", style = MaterialTheme.typography.bodyMedium)
            }
            items(shown, key = { it.id }) { item ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SnapView(item)
                    TextButton(onClick = { PovSnap.delete(context, item.id) }) { Text("刪除", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}
