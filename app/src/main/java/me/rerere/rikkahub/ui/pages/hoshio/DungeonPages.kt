package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.dungeon.DungeonState
import me.rerere.rikkahub.hoshio.dungeon.DungeonStats
import me.rerere.rikkahub.hoshio.dungeon.DungeonStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/** 目前聊天頁是哪個副本（不是副本就是 null） */
val LocalDungeon = compositionLocalOf<DungeonState?> { null }

/** 副本裡「悄悄說」的開關 */
object DungeonUi {
    val whisper = MutableStateFlow(false)
}

/** 每個副本的配色 */
data class DungeonPalette(val accent: Color, val tint: Color, val card: Color)

@Composable
fun dungeonPalette(theme: String): DungeonPalette {
    val dark = me.rerere.rikkahub.ui.theme.LocalDarkMode.current
    val accent = when (theme) {
        "cream" -> Color(0xFFA88A4E)
        "night" -> Color(0xFF8E9BD8)
        "crimson" -> Color(0xFFB4515A)
        "jade" -> Color(0xFF4E9682)
        "rose" -> Color(0xFFC07A93)
        else -> Color(0xFF4A7A8C) // fog
    }
    val tint = accent.copy(alpha = if (dark) .22f else .14f)
    return DungeonPalette(accent, tint, accent.copy(alpha = if (dark) .14f else .08f))
}

private val TIME_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm")

// =====================================================================
// 副本清單
// =====================================================================

@Composable
fun DungeonListPage() {
    val context = LocalContext.current
    val nav = LocalNavController.current
    val chatService = koinInject<ChatService>()
    val list by DungeonStore.all.collectAsState()
    LaunchedEffect(Unit) { DungeonStore.load(context) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DungeonState?>(null) }

    PlayScaffold("副本遊樂場") { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("和他一起進副本：他當主持人，也是你的搭檔。數值、背包、規則都會記下來，擲骰由系統擲，他沒辦法放水。",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 開新副本") }
                }
            }
            items(list.orEmpty(), key = { it.id }) { d ->
                val pal = dungeonPalette(d.theme)
                Card(
                    colors = CardDefaults.cardColors(containerColor = pal.tint),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().clickable { navigateToChatPage(nav, chatId = Uuid.parse(d.conversationId)) },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (d.number.isNotBlank()) Text(d.number, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp, color = pal.accent, style = MaterialTheme.typography.labelMedium)
                        Text(d.title.ifBlank { "準備中…" }, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            listOfNotNull(
                                when (d.status) { "cleared" -> "✓ 通關"; "failed" -> "✕ 失敗"; "bad_end" -> "☠ Bad End"; else -> "進行中" },
                                if (d.round > 0) "第 ${d.round} 輪" else null,
                                d.ending.ifBlank { null },
                                Instant.ofEpochMilli(d.updatedAt).atZone(ZoneId.systemDefault()).format(TIME_FMT),
                            ).joinToString("・"),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row { Spacer(Modifier.weight(1f)); TextButton(onClick = { deleting = d }) { Text("刪除") } }
                    }
                }
            }
        }
    }

    if (creating) {
        var world by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("開新副本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("留空就是隨機副本，讓他自己生世界觀。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = world, onValueChange = { world = it.take(200) }, label = { Text("世界觀（可空白）") },
                        placeholder = { Text("例如：維多利亞海邊小鎮、賽博龐克、古代宮廷") })
                    OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, label = { Text("想要的感覺（可空白）") },
                        placeholder = { Text("例如：規則怪談、偏恐怖、要有甜的部分") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = Uuid.random()
                    val request = listOfNotNull(
                        world.trim().ifBlank { null }?.let { "世界觀：$it" },
                        note.trim().ifBlank { null }?.let { "想要的感覺：$it" },
                    ).joinToString("；").ifBlank { "隨機副本" }
                    DungeonStore.create(context, id.toString(), request)
                    chatService.sendMessage(id, listOf(UIMessagePart.Text("【開新副本】$request")))
                    creating = false
                    navigateToChatPage(nav, chatId = id)
                }) { Text("進入副本") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("刪除《${d.displayName()}》？") },
            text = { Text("只刪副本的狀態紀錄，對話會留在對話紀錄裡。") },
            confirmButton = { TextButton(onClick = { DungeonStore.remove(context, d.id); deleting = null }) { Text("刪除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

// =====================================================================
// 聊天頁上方的狀態列
// =====================================================================

@Composable
fun DungeonHud(state: DungeonState, herName: String, hisName: String) {
    val pal = dungeonPalette(state.theme)
    val whisper by DungeonUi.whisper.collectAsState()
    var showStatus by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(16.dp)).background(pal.tint)
            .clickable { showStatus = true }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HudPlayer(hisName, state.him, pal.accent, Modifier.weight(1f))
            HudPlayer(herName, state.her, pal.accent, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.mainQuest.isNotBlank()) Text("主線 ${state.mainProgress}/${state.mainTotal}", style = MaterialTheme.typography.labelSmall, color = pal.accent)
            Spacer(Modifier.width(8.dp))
            Text("● ${state.mood}", style = MaterialTheme.typography.labelSmall, color = moodColor(state.mood))
            Spacer(Modifier.width(8.dp))
            Text("破綻 ${state.exposure}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("⟲ ${state.rewinds}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = whisper,
                onClick = { DungeonUi.whisper.value = !whisper },
                label = { Text(if (whisper) "悄悄說中" else "悄悄說", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(28.dp),
            )
            TextButton(onClick = { showStatus = true }) { Text("狀態", color = pal.accent) }
        }
    }
    if (showStatus) DungeonStatusSheet(state, herName, hisName) { showStatus = false }
}

@Composable
private fun HudPlayer(name: String, s: DungeonStats, accent: Color, modifier: Modifier) {
    Column(modifier) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("♥", fontSize = 11.sp, color = accent)
            LinearProgressIndicator(progress = { s.health / 100f }, modifier = Modifier.weight(1f).height(3.dp), color = accent, strokeCap = StrokeCap.Round)
            Text("${s.health}", style = MaterialTheme.typography.labelSmall)
            Text("✧", fontSize = 11.sp, color = accent)
            LinearProgressIndicator(progress = { s.sanity / 100f }, modifier = Modifier.weight(1f).height(3.dp), color = accent, strokeCap = StrokeCap.Round)
            Text("${s.sanity}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun moodColor(mood: String) = when {
    mood.contains("崩") || mood.contains("危") -> Color(0xFFC0392B)
    mood.contains("緊") -> Color(0xFFD68A2E)
    else -> Color(0xFF7A8A8F)
}

// =====================================================================
// 狀態頁
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DungeonStatusSheet(state: DungeonState, herName: String, hisName: String, onDismiss: () -> Unit) {
    val pal = dungeonPalette(state.theme)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("狀態", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pal.tint)
                        .border(1.dp, pal.accent.copy(alpha = .5f), RoundedCornerShape(22.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (state.number.isNotBlank()) Text(state.number, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, color = pal.accent)
                    Text(state.title.ifBlank { "準備中…" }, fontFamily = FontFamily.Serif, fontSize = 30.sp)
                    if (state.identityHer.isNotBlank()) Text("$herName・${state.identityHer}", style = MaterialTheme.typography.bodyMedium)
                    if (state.identityHim.isNotBlank()) Text("$hisName・${state.identityHim}", style = MaterialTheme.typography.bodyMedium)
                    if (state.location.isNotBlank()) Text("⚓ ${state.location}", style = MaterialTheme.typography.bodyMedium)
                    if (state.time.isNotBlank()) Text("◷ ${state.time}", style = MaterialTheme.typography.bodyMedium)
                    if (state.deadline.isNotBlank()) Text("⧗ 時限・${state.deadline}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC0392B))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("● ${state.mood}", style = MaterialTheme.typography.labelMedium, color = moodColor(state.mood))
                        if (state.mainQuest.isNotBlank()) Text("　主線 ${state.mainProgress}/${state.mainTotal}", style = MaterialTheme.typography.labelMedium, color = pal.accent)
                        Spacer(Modifier.weight(1f))
                        Text("身份破綻值 ${state.exposure}/100", style = MaterialTheme.typography.labelMedium)
                    }
                    if (state.status != "active") Text(
                        when (state.status) { "cleared" -> "✓ 已通關"; "bad_end" -> "☠ Bad End"; else -> "✕ 失敗" } + if (state.ending.isNotBlank()) "・${state.ending}" else "",
                        style = MaterialTheme.typography.titleSmall, color = pal.accent,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCardColumn(hisName, state.him, state.specialName, pal, false, Modifier.weight(1f))
                    StatCardColumn(herName, state.her, state.specialName, pal, true, Modifier.weight(1f))
                }
            }
            if (state.mainQuest.isNotBlank() || state.sideQuests.isNotEmpty()) item {
                Section("⚑ 任務", pal) {
                    if (state.mainQuest.isNotBlank()) {
                        Row { Text("主線", color = pal.accent, style = MaterialTheme.typography.labelLarge); Spacer(Modifier.weight(1f)); Text("${state.mainProgress}/${state.mainTotal}", color = pal.accent, style = MaterialTheme.typography.labelLarge) }
                        Text(state.mainQuest, style = MaterialTheme.typography.bodyMedium)
                    }
                    state.sideQuests.forEach { Text("支線・$it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (state.present.isNotEmpty()) item {
                Section("👥 角色", pal) { Row { Text("在場　", color = pal.accent, style = MaterialTheme.typography.labelLarge); Text(state.present.joinToString("、"), style = MaterialTheme.typography.bodyMedium) } }
            }
            if (state.log.isNotEmpty()) item {
                Section("↯ 這一場的經過", pal) {
                    state.log.takeLast(30).reversed().forEach { l ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.padding(top = 6.dp).size(7.dp).clip(RoundedCornerShape(50)).background(pal.accent))
                            Column {
                                Text("第 ${l.round} 輪" + if (l.time.isNotBlank()) "・${l.time}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(l.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (state.rolls.isNotEmpty()) item {
                Section("🎲 擲骰紀錄", pal) {
                    state.rolls.takeLast(10).reversed().forEach { r ->
                        Text("${r.who}・${r.skill}　${r.roll} / ${r.target}　${r.result}", style = MaterialTheme.typography.bodySmall,
                            color = if (r.result.contains("失敗")) Color(0xFFC0392B) else LocalContentColor.current)
                    }
                }
            }
            item {
                Section("🎒 背包（${state.items.size}/${DungeonStore.MAX_ITEMS}）", pal) {
                    if (state.items.isEmpty()) Text("空的", style = MaterialTheme.typography.bodySmall)
                    state.items.forEach { it ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, pal.accent.copy(alpha = .4f), RoundedCornerShape(14.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(it.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            val meta = listOf(it.type, it.duration).filter { s -> s.isNotBlank() }.joinToString("・")
                            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelMedium, color = pal.accent)
                            if (it.desc.isNotBlank() || it.owner.isNotBlank()) Text(listOf(if (it.owner.isNotBlank()) "${it.owner}持有" else "", it.desc).filter { s -> s.isNotBlank() }.joinToString("："),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (state.rules.isNotEmpty()) item {
                Section("⚠ 世界邏輯", pal) {
                    state.rules.forEach { Row { Text("◆  ", color = pal.accent, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) } }
                }
            }
            if (state.files.isNotEmpty()) item {
                Section("🗂 檔案", pal) {
                    state.files.forEach { f ->
                        var open by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.card).clickable { open = !open }.padding(12.dp)) {
                            Text(f.title, style = MaterialTheme.typography.titleSmall)
                            if (open) Text(f.content, style = MaterialTheme.typography.bodySmall) else Text(f.content, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Text("存檔點：${state.checkpoints.joinToString("、") { it.name }.ifBlank { "無" }}　剩餘重溯 ${state.rewinds} 次",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatCardColumn(name: String, s: DungeonStats, specialName: String, pal: DungeonPalette, highlight: Boolean, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(if (highlight) pal.accent.copy(alpha = .22f) else pal.card)
            .border(1.5.dp, pal.accent.copy(alpha = .7f), RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = pal.accent, fontWeight = FontWeight.SemiBold)
        DungeonStore.STAT_LABELS.forEach { (key, label) -> StatBar(label, s.get(key) ?: 0, 100, pal.accent) }
        if (specialName.isNotBlank()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.accent.copy(alpha = .25f)))
            StatBar(specialName, s.special, 100, pal.accent)
        }
    }
}

@Composable
private fun StatBar(label: String, value: Int, max: Int, color: Color) {
    Column {
        Row { Text(label, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.weight(1f)); Text("$value", style = MaterialTheme.typography.bodyMedium) }
        LinearProgressIndicator(progress = { (value.toFloat() / max).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = color,
            trackColor = color.copy(alpha = .15f), strokeCap = StrokeCap.Round)
    }
}

@Composable
private fun Section(title: String, pal: DungeonPalette, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(pal.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = pal.accent, letterSpacing = 2.sp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(pal.accent.copy(alpha = .35f)))
        }
        content()
    }
}

// =====================================================================
// 副本裡的文字：旁白、<sys> 系統框、<whisper> 悄悄說
// =====================================================================

private val BLOCK = Regex("""<(sys|whisper)>([\s\S]*?)(</\1>|$)""")

@Composable
fun DungeonProse(text: String, streaming: Boolean, hisName: String) {
    val state = LocalDungeon.current
    val pal = dungeonPalette(state?.theme ?: "fog")
    val pieces = remember(text) {
        // 串流時結尾可能停在沒寫完的 <sys / </whis
        val text = text.replace(Regex("""<[/a-z]{0,8}$"""), "")
        val out = mutableListOf<Pair<String, String>>()
        var last = 0
        BLOCK.findAll(text).forEach { m ->
            if (m.range.first > last) out += "prose" to text.substring(last, m.range.first)
            out += m.groupValues[1] to m.groupValues[2]
            last = m.range.last + 1
        }
        if (last < text.length) out += "prose" to text.substring(last)
        out.map { (k, v) -> k to v.trim() }.filter { it.second.isNotBlank() }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pieces.forEachIndexed { i, (kind, body) ->
            val live = streaming && i == pieces.lastIndex
            when (kind) {
                "sys" -> Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clip(RoundedCornerShape(12.dp)).background(pal.card)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(pal.accent))
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        MarkdownBlock(content = body.lines().joinToString("  \n"), streaming = live,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
                    }
                }
                "whisper" -> WhisperCard("$hisName 悄悄說", body, live, pal.accent)
                else -> SceneProse(body, live)
            }
        }
    }
}

@Composable
fun WhisperCard(label: String, body: String, streaming: Boolean, accent: Color) {
    Column(
        Modifier.fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = accent.copy(alpha = .6f),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                )
            }
            .clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = .08f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = accent, letterSpacing = 1.sp)
        MarkdownBlock(content = body, streaming = streaming)
    }
}
