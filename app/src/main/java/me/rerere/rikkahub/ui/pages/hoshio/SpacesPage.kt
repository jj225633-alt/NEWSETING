package me.rerere.rikkahub.ui.pages.hoshio

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.hoshio.spaces.SPACE_PAPERS
import me.rerere.rikkahub.hoshio.spaces.SecretBaseStore
import me.rerere.rikkahub.hoshio.spaces.SharedSpaceStore
import me.rerere.rikkahub.hoshio.spaces.SpacePost
import me.rerere.rikkahub.hoshio.spaces.SpaceStory
import me.rerere.rikkahub.hoshio.together.AlbumStore
import me.rerere.rikkahub.hoshio.together.BY_HER
import me.rerere.rikkahub.hoshio.together.BY_HIM
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SPACE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private fun Long.spaceFmt() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(SPACE_TIME)

/** 信纸：底色、墨色和一点纹路 */
@Composable
private fun SpacePaper(paper: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val background = when (paper) {
        "kraft" -> Color(0xFFE8D6B6)
        "floral" -> Color(0xFFF9F0F2)
        "journal" -> Color(0xFFF0F2E8)
        "midnight" -> Color(0xFF252A43)
        else -> Color(0xFFFBF7EE)
    }
    val ink = if (paper == "midnight") Color(0xFFF5EBD9) else Color(0xFF493F43)
    Box(
        modifier.clip(MaterialTheme.shapes.large).background(background).drawBehind {
            when (paper) {
                "letter" -> { var y = 52.dp.toPx(); while (y < size.height) { drawLine(ink.copy(alpha = .06f), Offset(0f, y), Offset(size.width, y), 1f); y += 26.dp.toPx() } }
                "journal" -> { var x = 0f; while (x < size.width) { var y = 0f; while (y < size.height) { drawCircle(ink.copy(alpha = .10f), 1f, Offset(x, y)); y += 16.dp.toPx() }; x += 16.dp.toPx() } }
                "floral" -> { for (i in 0..5) drawCircle(Color(0xFFD1A0AD).copy(alpha = .14f), 16.dp.toPx(), Offset(size.width - 18.dp.toPx() + (i % 2) * 15.dp.toPx(), (i * 43).dp.toPx())) }
                "midnight" -> { for (i in 0..18) drawCircle(Color(0xFFEBD5A7).copy(alpha = .18f), (1 + i % 2).toFloat(), Offset(size.width * ((i * 37 % 97) / 97f), size.height * ((i * 23 % 89) / 89f))) }
            }
        },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides ink) { content() }
    }
}

@Composable
fun SpacesPage() {
    var tab by remember { mutableStateOf(0) }
    PlayScaffold("秘密基地與共同空間") { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("✉ 秘密基地") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("🌿 共同空間") })
            }
            if (tab == 0) SecretBaseTab() else SharedSpaceTab()
        }
    }
}

// ───────────────────────── 秘密基地 ─────────────────────────

@Composable
private fun SecretBaseTab() {
    val context = LocalContext.current
    val stories by SecretBaseStore.items.collectAsState()
    remember { SecretBaseStore.load(context) }
    val callHim = rememberCallHim()
    var editing by remember { mutableStateOf<SpaceStory?>(null) }
    var creating by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<SpaceStory?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("你寫序章（標題和原指令），他續寫番外。主線之外的故事放在這裡，兩個人都看得到。", style = MaterialTheme.typography.bodyMedium)
        }
        item { Button(onClick = { creating = true }) { Text("寫一個序章") } }
        items(SecretBaseStore.sorted(stories), key = { it.id }) { story ->
            SpacePaper(story.paper, Modifier.fillMaxWidth().heightIn(min = 140.dp).clickable { reading = story }) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text((if (story.pinned) "📌 " else "") + story.title, fontFamily = FontFamily.Serif, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("原指令：" + story.prompt, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        story.body.ifBlank { "（等他來寫…）" },
                        fontFamily = FontFamily.Serif,
                        fontSize = 15.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${SPACE_PAPERS[story.paper] ?: ""}・${story.updatedAt.spaceFmt()}", fontSize = 10.sp)
                }
            }
        }
    }

    if (creating || editing != null) {
        val old = editing
        var title by remember(old) { mutableStateOf(old?.title.orEmpty()) }
        var prompt by remember(old) { mutableStateOf(old?.prompt.orEmpty()) }
        var body by remember(old) { mutableStateOf(old?.body.orEmpty()) }
        var paper by remember(old) { mutableStateOf(old?.paper ?: "letter") }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (old == null) "寫一個序章" else "編輯") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("標題") }, singleLine = true)
                    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("原指令／序章") }, minLines = 3)
                    if (old != null) OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("正文") }, minLines = 4)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SPACE_PAPERS.forEach { (key, name) -> FilterChip(selected = paper == key, onClick = { paper = key }, label = { Text(name) }) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank() && prompt.isNotBlank(), onClick = {
                    SecretBaseStore.saveHuman(context, old?.id, title, prompt, if (old != null) body else null, paper)
                    val isNew = old == null
                    creating = false
                    editing = null
                    if (isNew) {
                        val created = SecretBaseStore.load(context).maxByOrNull { it.createdAt }
                        callHim(
                            "秘密基地",
                            "She wrote a new premise in 秘密基地: 「${title.trim()}」 — ${prompt.take(2000)}\n" +
                                "Write the side story with secret_base write (id ${created?.id}), then tell her briefly that it is ready.",
                        )
                    }
                }) { Text(if (old == null) "請他寫" else "儲存") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("取消") } },
        )
    }

    reading?.let { story ->
        var confirmDelete by remember(story.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { reading = null },
            title = { Text(story.title) },
            text = {
                SpacePaper(story.paper, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("原指令：" + story.prompt, fontSize = 12.sp)
                        Text(story.body.ifBlank { "（他還沒寫）" }, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 26.sp)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        SecretBaseStore.update(context) { list -> list.map { if (it.id == story.id) it.copy(pinned = !it.pinned) else it } }
                        reading = null
                    }) { Text(if (story.pinned) "取消置頂" else "置頂") }
                    TextButton(onClick = { editing = story; reading = null }) { Text("編輯") }
                }
            },
            dismissButton = {
                if (confirmDelete) {
                    TextButton(onClick = { SecretBaseStore.update(context) { it.filterNot { s -> s.id == story.id } }; reading = null }) { Text("確定刪除") }
                } else {
                    TextButton(onClick = { confirmDelete = true }) { Text("刪除") }
                }
            },
        )
    }
}

// ───────────────────────── 共同空间 ─────────────────────────

@Composable
private fun SharedSpaceTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val posts by SharedSpaceStore.items.collectAsState()
    remember { SharedSpaceStore.load(context) }
    val callHim = rememberCallHim()
    var draft by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val names = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    AlbumStore.importImage(context, uri)?.let { name ->
                        // 存进共同空间自己的资料夹
                        val from = File(AlbumStore.dir(context), name)
                        val to = SharedSpaceStore.image(context, name)
                        if (from.renameTo(to)) name else null
                    }
                }
            }
            images = (images + names).take(9)
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, placeholder = { Text("想跟他分享什麼？") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    if (images.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            images.forEach { name ->
                                AsyncImage(
                                    model = android.net.Uri.fromFile(SharedSpaceStore.image(context, name)).toString(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small),
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("加照片") }
                        Button(enabled = draft.isNotBlank() || images.isNotEmpty(), onClick = {
                            val post = SharedSpaceStore.publish(context, BY_HER, draft, images)
                            draft = ""
                            images = emptyList()
                            callHim(
                                "共同空間",
                                "She just posted in 共同空間 (post ${post.id}): \"${post.text.take(1000)}\"" +
                                    (if (post.images.isNotEmpty()) " with ${post.images.size} photo(s) (use shared_space view to see them)" else "") +
                                    ". Like it and leave a comment there with shared_space, then you may say a little in chat.",
                            )
                        }) { Text("發布") }
                    }
                }
            }
        }
        if (posts.isEmpty()) item { Text("這裡還沒有動態。發一則，或等他來發。", style = MaterialTheme.typography.bodyMedium) }
        items(posts, key = { it.id }) { post -> PostCard(post) }
    }
}

@Composable
private fun PostCard(post: SpacePost) {
    val context = LocalContext.current
    var comment by remember(post.id) { mutableStateOf("") }
    var confirmDelete by remember(post.id) { mutableStateOf(false) }
    val liked = BY_HER in post.likes
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text(if (post.by == BY_HIM) "🌙 他" else "🦊 你", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(post.at.spaceFmt(), style = MaterialTheme.typography.labelSmall)
            }
            if (post.text.isNotBlank()) Text(post.text, style = MaterialTheme.typography.bodyLarge)
            if (post.images.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    post.images.forEach { name ->
                        AsyncImage(
                            model = android.net.Uri.fromFile(SharedSpaceStore.image(context, name)).toString(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(if (post.images.size == 1) 220.dp else 96.dp).clip(MaterialTheme.shapes.medium),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { SharedSpaceStore.like(context, post.id, BY_HER, !liked) }) {
                    Text((if (liked) "❤️ " else "🤍 ") + post.likes.size.takeIf { it > 0 }?.toString().orEmpty())
                }
                if (BY_HIM in post.likes) Text("他按了讚", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 14.dp))
                Box(Modifier.weight(1f))
                if (confirmDelete) {
                    TextButton(onClick = { SharedSpaceStore.delete(context, post.id, BY_HER) }) { Text("確定刪除") }
                } else {
                    TextButton(onClick = { confirmDelete = true }) { Text("刪除") }
                }
            }
            post.comments.forEach { c ->
                Text((if (c.by == BY_HIM) "他：" else "你：") + c.text, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = comment, onValueChange = { comment = it }, placeholder = { Text("留言") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(enabled = comment.isNotBlank(), onClick = {
                    SharedSpaceStore.comment(context, post.id, BY_HER, comment)
                    comment = ""
                }) { Text("送出") }
            }
        }
    }
}
