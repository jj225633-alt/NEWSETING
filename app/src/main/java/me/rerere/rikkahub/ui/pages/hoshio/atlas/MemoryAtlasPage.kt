package me.rerere.rikkahub.ui.pages.hoshio.atlas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.isActive
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.hoshio.atlas.OrbisAtlasDisplayMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.rerere.rikkahub.hoshio.atlas.OrbisAtlasGalaxy as Galaxy
import me.rerere.rikkahub.hoshio.atlas.OrbisMemoryAtlas as Atlas

private val AtlasInk = Color(0xFFF2EBDD)
private val AtlasMuted = Color(0xFFBAA484)
internal val AtlasGold = Color(0xFFD4B77C)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

/** 一颗记忆星：哪个档案、哪一行 */
private data class MemoryStar(val type: String, val path: String, val text: String, val updatedAt: Long)

private fun typeOf(path: String): String {
    val p = path.lowercase()
    return when {
        p.startsWith("/profile") -> "關於你"
        p.startsWith("/preferences") -> "偏好"
        p.startsWith("/people") -> "人物"
        p.startsWith("/topics") -> "話題"
        else -> "日常"
    }
}

/** 把每个记忆档案拆成一行一行的星星；同一个档案里相邻的星星连成星座 */
private fun buildStars(files: List<me.rerere.rikkahub.data.model.MemoryFile>): Pair<List<MemoryStar>, List<Atlas.Edge>> {
    val stars = mutableListOf<MemoryStar>()
    val edges = mutableListOf<Atlas.Edge>()
    files.sortedBy { it.path }.forEach { file ->
        val start = stars.size
        file.content.lines()
            .map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("*") }
            .map { it.trimStart('-', '*', ' ').replace(Regex("^\\[[a-z]+]\\s*"), "").trim() }
            .filter { it.isNotEmpty() }
            .forEach { if (stars.size < 2000) stars += MemoryStar(typeOf(file.path), file.path, it, file.updatedAt) }
        for (i in start + 1 until stars.size) edges += Atlas.Edge(i - 1, i)
        // 档案里第一颗连到最后一颗，围成一个小星座
        if (stars.size - start > 2) edges += Atlas.Edge(start, stars.size - 1)
    }
    return stars to edges
}

/**
 * 星汐「记忆星图」：把他记得的关于你的事画成一片星系（绘图移植自 Orbis 的记忆星盘）。
 * 每一颗星是记忆里的一行，颜色代表分类；点一下看那一行。
 */
@Composable
fun MemoryAtlasPage() {
    val settings = LocalSettings.current
    val memoryRepository = koinInject<MemoryRepository>()
    val assistantId = settings.assistantId.toString()
    var stars by remember { mutableStateOf<List<MemoryStar>>(emptyList()) }
    var graph by remember { mutableStateOf<Atlas.Graph?>(null) }
    var yaw by remember { mutableDoubleStateOf(-.3) }
    var pitch by remember { mutableDoubleStateOf(-.62) }
    var zoom by remember { mutableDoubleStateOf(1.0) }
    var clock by remember { mutableDoubleStateOf(0.0) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var paused by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(true) }
    var galaxyMode by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(assistantId) {
        val (list, edges) = buildStars(memoryRepository.getFiles(assistantId))
        stars = list
        graph = if (list.isEmpty()) null else Atlas.metadataOnly(
            list.map { Atlas.Star("", it.type, Instant.ofEpochMilli(it.updatedAt).toString()) },
            edges,
        )
    }

    fun camera() = Atlas.Camera(yaw, pitch, zoom)
    fun updateCamera(value: Atlas.Camera) { yaw = value.yaw; pitch = value.pitch; zoom = value.zoom }
    val displayMode = if (galaxyMode) OrbisAtlasDisplayMode.GALAXY else OrbisAtlasDisplayMode.LIGHTWEIGHT
    val animate = !galaxyMode && graph != null && resumed && !paused && selected == null && !dragging
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (isActive) androidx.compose.runtime.withFrameNanos { now ->
            if (last == 0L) last = now
            else if (now - last >= 30_000_000L) {
                val seconds = ((now - last) / 1_000_000_000.0).coerceAtMost(Atlas.MAX_FRAME_SECONDS)
                updateCamera(Atlas.advance(camera(), seconds))
                clock += seconds
                last = now
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF080A17))) {
        graph?.let { g ->
            OrbisMemoryAtlasCanvas(
                demo = g,
                camera = { camera() },
                clock = { clock },
                selected = selected,
                motionLabel = if (paused) "自轉已暫停" else "拖動旋轉・雙指縮放・輕觸星點",
                motionAllowed = !galaxyMode && resumed && !paused,
                onCamera = ::updateCamera,
                onSelected = { selected = it },
                onDragging = { dragging = it },
                onReset = { updateCamera(Atlas.Camera()); selected = null },
                onToggleMotion = { paused = !paused },
                modifier = Modifier.fillMaxSize(),
                displayMode = displayMode,
                sourceDescription = "他的記憶筆記",
                typeColor = { type -> Galaxy.tintForType(type).color() },
            )
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton()
                Column(Modifier.weight(1f)) {
                    Text("MEMORY ATLAS", color = AtlasMuted, fontSize = 10.sp, letterSpacing = 2.sp)
                    Text("記憶星圖", color = AtlasInk, fontSize = 24.sp, fontFamily = FontFamily.Serif, letterSpacing = 4.sp)
                }
                TextButton(onClick = { paused = !paused }) { Text(if (paused) "繼續轉" else "暫停", color = AtlasInk) }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !galaxyMode, onClick = { galaxyMode = false }, label = { Text("星盤", color = AtlasInk) })
                FilterChip(selected = galaxyMode, onClick = { galaxyMode = true; selected = null }, label = { Text("銀河", color = AtlasInk) })
            }
            Text(
                if (stars.isEmpty()) "還沒有記憶。和他多聊一點，他記住的事會變成這裡的星星。" else "${stars.size} 顆星・每顆是他記得的一件事",
                color = AtlasMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        // 图例
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Atlas.HOSHIO_TYPES.forEach { type ->
                val tint = Galaxy.tintForType(type)
                Box(Modifier.size(8.dp).background(tint.color(), CircleShape))
                Text(type, color = AtlasMuted, fontSize = 11.sp)
            }
        }
        selected?.let { index ->
            stars.getOrNull(index)?.let { star ->
                Card(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).padding(bottom = 40.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xE61B1F3B)),
                ) {
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("✦ ${star.type}・${star.path}", color = AtlasMuted, fontSize = 11.sp)
                        Text(star.text, color = AtlasInk, fontSize = 15.sp, lineHeight = 22.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "更新於 " + Instant.ofEpochMilli(star.updatedAt).atZone(ZoneId.systemDefault()).format(DAY),
                                color = AtlasMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { selected = null }) { Text("關閉", color = AtlasInk) }
                        }
                    }
                }
            }
        }
    }
}
