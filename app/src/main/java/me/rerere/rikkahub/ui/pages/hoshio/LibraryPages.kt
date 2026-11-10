package me.rerere.rikkahub.ui.pages.hoshio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.hoshio.library.Book
import me.rerere.rikkahub.hoshio.library.BookNote
import me.rerere.rikkahub.hoshio.library.LibraryStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.toast.ToastType

/**
 * 书房：汇入的书，两个人各自的进度。
 */
@Composable
fun LibraryPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val books by LibraryStore.books.collectAsState()
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Book?>(null) }
    LaunchedEffect(Unit) { LibraryStore.load(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = FileUtils.getFileNameFromUri(context, uri) ?: "book.txt"
        importing = true
        scope.launch {
            runCatching { LibraryStore.import(context, uri, name) }
                .onSuccess { toaster.show(message = "已加入「${it.title}」，共 ${it.partCount} 段", type = ToastType.Success) }
                .onFailure { toaster.show(message = it.message ?: "匯入失敗", type = ToastType.Error) }
            importing = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("書房") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!importing) picker.launch(
                        arrayOf("text/plain", "text/markdown", "application/epub+zip", "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream")
                    )
                },
                icon = { Icon(HugeIcons.Add01, null) },
                text = { Text(if (importing) "匯入中…" else "加入一本書") },
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "加入沒有加密的 TXT、EPUB、PDF、DOCX，就能和他一起讀、一起寫批註。書只存在你的手機裡。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(books, key = { it.id }) { book ->
                Card(
                    onClick = { navController.navigate(Screen.BookReader(book.id)) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("📖 ${book.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("我讀到 ${book.humanProgress}／${book.partCount}", style = MaterialTheme.typography.labelMedium)
                            LinearProgressIndicator(progress = { book.humanProgress / book.partCount.toFloat() }, modifier = Modifier.fillMaxWidth())
                            Text("他讀到 ${book.aiProgress}／${book.partCount}", style = MaterialTheme.typography.labelMedium)
                            LinearProgressIndicator(
                                progress = { book.aiProgress / book.partCount.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        IconButton(onClick = { deleting = book }) { Icon(HugeIcons.Delete01, contentDescription = "刪除") }
                    }
                }
            }
        }
    }

    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("把「${book.title}」移出書房？") },
            text = { Text("書的內容和兩個人的批註都會刪掉。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { LibraryStore.delete(context, book.id) }
                }) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

/**
 * 读书页：一次一段，下面是两个人的批注；可以自己写批注。
 */
@Composable
fun BookReaderPage(bookId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by LibraryStore.books.collectAsState()
    val notesVersion by LibraryStore.notesVersion.collectAsState()
    LaunchedEffect(Unit) { LibraryStore.load(context) }
    val book = books.firstOrNull { it.id == bookId }
    var part by remember(book?.id) { mutableIntStateOf((book?.humanProgress ?: 0).coerceAtLeast(1)) }
    var writing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val text by produceState<String?>(null, bookId, part) { value = LibraryStore.readPart(context, bookId, part) }
    val notes by produceState(emptyList<BookNote>(), bookId, part, notesVersion) {
        value = LibraryStore.notes(context, bookId).filter { it.part == part }
    }
    LaunchedEffect(part, book?.id) {
        val b = book ?: return@LaunchedEffect
        if (part > b.humanProgress) LibraryStore.setProgress(context, bookId, LibraryStore.AUTHOR_HUMAN, part)
        listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: "書", maxLines = 1) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (book == null) {
            Text("找不到這本書", modifier = Modifier.padding(innerPadding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "第 $part／${book.partCount} 段" + if (book.aiProgress >= part) "・他也讀過這段了" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                Text(text ?: "讀取中…", style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.3f))
            }
            if (notes.isNotEmpty()) {
                item { Text("批註", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            }
            items(notes, key = { it.id }) { note ->
                val isAi = note.author == LibraryStore.AUTHOR_AI
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAi) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (isAi) "他" else "我", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            if (note.quote.isNotBlank()) Text("「${note.quote}」", style = MaterialTheme.typography.bodySmall)
                            Text(note.note, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { scope.launch { LibraryStore.deleteNote(context, bookId, note.id) } }) {
                            Icon(HugeIcons.Delete01, contentDescription = "刪除批註")
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = part > 1, onClick = { part -= 1 }) { Text("‹ 上一段") }
                    TextButton(onClick = { writing = true }) { Text("寫批註") }
                    TextButton(enabled = part < book.partCount, onClick = { part += 1 }) { Text("下一段 ›") }
                }
            }
        }
    }

    if (writing && book != null) {
        var quote by remember { mutableStateOf("") }
        var noteText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text("寫批註") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = quote, onValueChange = { quote = it.take(200) }, label = { Text("引用的句子（可留空）") })
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("你的想法") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    )
                    Text("他下次讀到這段時會看到你的批註", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = noteText.isNotBlank(), onClick = {
                    val n = BookNote(part = part, quote = quote.trim(), note = noteText.trim(), author = LibraryStore.AUTHOR_HUMAN)
                    writing = false
                    scope.launch { LibraryStore.addNote(context, bookId, n) }
                }) { Text("儲存") }
            },
            dismissButton = { TextButton(onClick = { writing = false }) { Text("取消") } },
        )
    }
}
