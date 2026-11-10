package me.rerere.rikkahub.ui.pages.hoshio

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.hoshio.HoshioChat
import me.rerere.rikkahub.hoshio.play.PetStore
import me.rerere.rikkahub.hoshio.play.TurtleSoup
import me.rerere.rikkahub.hoshio.play.TurtleSoupStore
import me.rerere.rikkahub.hoshio.screen.HoshioAccessibilityService
import me.rerere.rikkahub.hoshio.screen.WatchTogetherService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PLAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private fun Long.playFmt() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(PLAY_FMT)

/** 放一则提示给他，然后跳到聊天室 */
@Composable
internal fun rememberCallHim(): (String, String) -> Unit {
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    return { title, text ->
        scope.launch {
            val id = HoshioChat.post(title, text)
            if (id == null) {
                toaster.show(message = "請先和他聊一句，才能在這裡叫他", type = ToastType.Warning)
            } else {
                navigateToChatPage(navigator = navController, chatId = id)
            }
        }
    }
}

@Composable
internal fun PlayScaffold(title: String, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.topBarColors.containerColor,
        content = content,
    )
}

// ───────────────────────── 海龟汤 ─────────────────────────

@Composable
fun TurtleSoupPage() {
    val context = LocalContext.current
    val games by TurtleSoupStore.games.collectAsState()
    remember { TurtleSoupStore.load(context) }
    val current = games.firstOrNull { it.endedAt == null }
    val callHim = rememberCallHim()
    var writing by remember { mutableStateOf(false) }
    var revealing by remember { mutableStateOf<TurtleSoup?>(null) }
    var giveUpConfirm by remember { mutableStateOf(false) }

    PlayScaffold("海龜湯") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "出題的人心裡有一個完整的故事（湯底），只給對方看開頭（謎面）。猜的人只能問「是／不是」的問題，一步步拼出真相。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (current != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                (if (current.host == "him") "他出的題・" else "你出的題・") + current.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(current.surface, style = MaterialTheme.typography.bodyLarge)
                            Text("已經問了 ${current.questions} 題・${current.startedAt.playFmt()} 開始", style = MaterialTheme.typography.labelMedium)
                            if (current.host == "her") {
                                Text("湯底（只有你看得到）：${current.answer}", style = MaterialTheme.typography.bodySmall)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    callHim(
                                        "海龜湯",
                                        if (current.host == "him") {
                                            "She wants to keep playing the turtle soup you hosted (\"${current.title}\"). Call turtle_soup status if you need the answer, and keep answering her questions."
                                        } else {
                                            "She is hosting a turtle soup for you. Call turtle_soup status to read the surface, then guess by asking her yes/no questions one at a time."
                                        },
                                    )
                                }) { Text("去聊天室玩") }
                                if (current.host == "him") {
                                    OutlinedButton(onClick = { giveUpConfirm = true }) { Text("投降，看湯底") }
                                } else {
                                    OutlinedButton(onClick = { TurtleSoupStore.end(context, solved = true, note = "他猜到了") }) { Text("他猜到了") }
                                    TextButton(onClick = { TurtleSoupStore.end(context, solved = false, note = "沒猜到") }) { Text("結束") }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("開一碗新的湯", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Button(onClick = {
                                callHim(
                                    "海龜湯",
                                    "She wants to play 海龜湯 and wants you to host. Think of an original, fair puzzle (not too gory), " +
                                        "lock it in with turtle_soup action=start, then show her only the surface and wait for her questions.",
                                )
                            }, modifier = Modifier.fillMaxWidth()) { Text("請他出一題") }
                            FilledTonalButton(onClick = { writing = true }, modifier = Modifier.fillMaxWidth()) { Text("我來出題給他猜") }
                        }
                    }
                }
            }
            val past = games.filter { it.endedAt != null }
            if (past.isNotEmpty()) {
                item { Text("喝過的湯", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            }
            items(past, key = { it.id }) { game ->
                Card(
                    onClick = { revealing = game },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            (if (game.result == "solved") "🎉 " else "🥣 ") + game.title,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            (if (game.host == "him") "他出題" else "你出題") + "・問了 ${game.questions} 題・" +
                                (if (game.result == "solved") "猜到了" else "沒猜到") + "・${game.startedAt.playFmt()}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    if (writing) {
        var title by remember { mutableStateOf("") }
        var surface by remember { mutableStateOf("") }
        var answer by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text("出題給他") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("題目名稱") }, singleLine = true)
                    OutlinedTextField(value = surface, onValueChange = { surface = it }, label = { Text("謎面（他看得到）") }, minLines = 3)
                    OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text("湯底（他看不到）") }, minLines = 3)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = surface.isNotBlank() && answer.isNotBlank(),
                    onClick = {
                        TurtleSoupStore.start(context, title, surface, answer, host = "her")
                        writing = false
                        callHim("海龜湯", "She just hosted a turtle soup for you. Call turtle_soup status to read the surface, then start guessing by asking yes/no questions one at a time.")
                    },
                ) { Text("出題並叫他來猜") }
            },
            dismissButton = { TextButton(onClick = { writing = false }) { Text("取消") } },
        )
    }
    if (giveUpConfirm && current != null) {
        AlertDialog(
            onDismissRequest = { giveUpConfirm = false },
            title = { Text("真的要投降嗎？") },
            text = { Text("看了湯底這局就結束了") },
            confirmButton = {
                TextButton(onClick = {
                    giveUpConfirm = false
                    revealing = TurtleSoupStore.end(context, solved = false, note = "投降了")
                }) { Text("投降") }
            },
            dismissButton = { TextButton(onClick = { giveUpConfirm = false }) { Text("再想想") } },
        )
    }
    revealing?.let { game ->
        AlertDialog(
            onDismissRequest = { revealing = null },
            title = { Text(game.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("謎面", style = MaterialTheme.typography.labelLarge)
                    Text(game.surface, style = MaterialTheme.typography.bodyMedium)
                    Text("湯底", style = MaterialTheme.typography.labelLarge)
                    Text(game.answer, style = MaterialTheme.typography.bodyMedium)
                    if (game.note.isNotBlank()) Text(game.note, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { revealing = null }) { Text("好") } },
            dismissButton = {
                TextButton(onClick = { TurtleSoupStore.delete(context, game.id); revealing = null }) { Text("刪除") }
            },
        )
    }
}

// ───────────────────────── 宠物 ─────────────────────────

@Composable
fun PetPage() {
    val context = LocalContext.current
    val pet by PetStore.pet.collectAsState()
    val callHim = rememberCallHim()
    var renaming by remember { mutableStateOf(false) }
    var releasing by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        PetStore.load(context)
        onPauseOrDispose { }
    }

    PlayScaffold("一起養寵物") { innerPadding ->
        val p = pet
        if (p == null) {
            var species by remember { mutableStateOf(PetStore.SPECIES.first()) }
            var name by remember { mutableStateOf("") }
            Column(
                Modifier.fillMaxSize().padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("還沒有寵物。挑一隻帶回家吧，你在這裡照顧牠，他也會自己來看牠。", style = MaterialTheme.typography.bodyMedium)
                Text(species, fontSize = 72.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PetStore.SPECIES.forEach { s ->
                        FilterChip(selected = species == s, onClick = { species = s }, label = { Text(s, fontSize = 22.sp) })
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名字（也可以等等跟他一起取）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { PetStore.adopt(context, name, species, PetStore.BY_HER) }, modifier = Modifier.fillMaxWidth()) { Text("帶回家") }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(p.species, fontSize = 80.sp)
                            Text(p.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text("來家裡第 ${p.ageDays + 1} 天・${p.status}", style = MaterialTheme.typography.labelLarge)
                            StatBar("飽足", p.fullness)
                            StatBar("心情", p.happiness)
                            StatBar("清潔", p.cleanliness)
                        }
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("feed" to "🍖 餵食", "play" to "🧶 玩耍", "clean" to "🛁 洗澡", "pet" to "🤲 摸摸").forEach { (action, label) ->
                            FilledTonalButton(onClick = { PetStore.care(context, action, PetStore.BY_HER) }) { Text(label) }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            callHim("寵物", "She wants you to come see ${p.name}. Call our_pet status, take care of it if you like, and talk to her about it.")
                        }) { Text("叫他來看看牠") }
                        TextButton(onClick = { renaming = true }) { Text("改名") }
                        TextButton(onClick = { releasing = true }) { Text("送走") }
                    }
                }
                item { Text("照顧紀錄", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(p.log) { log ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (log.by == PetStore.BY_HIM) "他" else "你") + " " + log.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(log.at.playFmt(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(pet?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("改名") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { PetStore.rename(context, name, PetStore.BY_HER); renaming = false }) { Text("好") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } },
        )
    }
    if (releasing) {
        AlertDialog(
            onDismissRequest = { releasing = false },
            title = { Text("真的要送走${pet?.name.orEmpty()}嗎？") },
            text = { Text("牠的紀錄會一起消失") },
            confirmButton = { TextButton(onClick = { PetStore.release(context); releasing = false }) { Text("送走") } },
            dismissButton = { TextButton(onClick = { releasing = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun StatBar(label: String, value: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.weight(1f))
        Text("$value", style = MaterialTheme.typography.labelMedium)
    }
}

// ───────────────────────── 一起看螢幕 ─────────────────────────

@Composable
fun WatchTogetherPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    val state by WatchTogetherService.state.collectAsState()
    var serviceOn by remember { mutableStateOf(HoshioAccessibilityService.isRunning) }
    var interval by remember { mutableIntStateOf(3) }
    var topic by remember { mutableStateOf("") }

    LifecycleResumeEffect(Unit) {
        serviceOn = HoshioAccessibilityService.isRunning
        onPauseOrDispose { }
    }

    PlayScaffold("一起看螢幕") { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "開始後，他每隔一段時間看一眼你的螢幕，像坐在你旁邊一樣說一兩句，話會浮在螢幕上方（點一下就收起來）。" +
                    "結束時他會留一段觀後感。你在星汐裡面時他不會偷看。每看一次都會呼叫一次 AI。",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Text("需要 Android 11 以上才能使用", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            if (!serviceOn) {
                Card(
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("⚠️ 星汐的無障礙服務沒在執行，點這裡去打開（無障礙 → 已下載的應用程式 → 星汐）", modifier = Modifier.padding(16.dp))
                }
            }
            if (state.running) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("正在一起看" + (if (state.topic.isNotBlank()) "：${state.topic}" else ""), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("每 ${state.intervalMinutes} 分鐘看一次・已經看了 ${state.glances} 次", style = MaterialTheme.typography.labelLarge)
                        if (state.lastComment.isNotBlank()) Text("他剛剛說：${state.lastComment}", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { WatchTogetherService.stop(context) }, modifier = Modifier.fillMaxWidth()) { Text("結束，請他寫觀後感") }
                    }
                }
            } else {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("在看什麼？（可不填，例如：一起追劇、我在打遊戲）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("多久看一次", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5, 10).forEach { m ->
                        FilterChip(selected = interval == m, onClick = { interval = m }, label = { Text("$m 分鐘") })
                    }
                }
                Button(
                    enabled = serviceOn,
                    onClick = {
                        scope.launch {
                            // 确保他有「看螢幕」工具
                            runCatching {
                                settingsStore.update { settings ->
                                    settings.copy(assistants = settings.assistants.map {
                                        if (it.id == settings.assistantId) it.copy(localTools = (it.localTools + LocalToolOption.ScreenView).distinct()) else it
                                    })
                                }
                            }
                            if (HoshioChat.latestConversation() == null) {
                                toaster.show(message = "請先和他聊一句，才能一起看", type = ToastType.Warning)
                                return@launch
                            }
                            WatchTogetherService.start(context, interval, topic.trim())
                            toaster.show(message = "開始了！切到你要看的東西吧", type = ToastType.Success)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("開始一起看") }
            }
        }
    }
}
