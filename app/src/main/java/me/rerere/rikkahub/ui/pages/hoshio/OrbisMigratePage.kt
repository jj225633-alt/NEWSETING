package me.rerere.rikkahub.ui.pages.hoshio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.hoshio.migrate.OrbisMigration
import org.koin.compose.koinInject

/**
 * 從 Orbis 搬家：對話、記憶、圖片、寵物、身體狀態、渴望，再加上花園。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrbisMigratePage() {
    val context = LocalContext.current
    val appScope = koinInject<AppScope>()

    var preview by remember { mutableStateOf<OrbisMigration.Preview?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var conversations by remember { mutableStateOf(true) }
    var memories by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf(true) }
    var pet by remember { mutableStateOf(true) }
    var body by remember { mutableStateOf(true) }
    var desires by remember { mutableStateOf(true) }
    var spaces by remember { mutableStateOf(true) }
    var mcp by remember { mutableStateOf(true) }
    var replace by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }

    var garden by remember { mutableStateOf<OrbisMigration.GardenPreview?>(null) }
    var hisNames by remember { mutableStateOf(setOf<String>()) }
    var gardenReport by remember { mutableStateOf<List<String>>(emptyList()) }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; status = "正在讀備份…"; report = emptyList()
        appScope.launch {
            runCatching { OrbisMigration.prepare(context, uri) }
                .onSuccess { p ->
                    preview = p
                    selected = p.assistants.map { it.id }.toSet()
                    status = if (p.hasDatabase) "" else "這份備份沒有包含資料庫，匯出時記得勾「資料庫」。"
                }
                .onFailure { status = "讀不了這個檔案：${it.message}" }
            busy = false
        }
    }
    val pickGarden = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        gardenReport = emptyList()
        appScope.launch {
            runCatching { OrbisMigration.readGarden(context, uri) }
                .onSuccess { g ->
                    garden = g
                    val names = preview?.assistants?.map { it.name }.orEmpty().toSet() + "時澈"
                    hisNames = g.authors.filter { it in names }.toSet()
                }
                .onFailure { gardenReport = listOf("讀不了這個檔案：${it.message}") }
        }
    }

    PlayScaffold("從 Orbis 搬家") { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "把 Orbis 的東西搬進星汐。只會「新增」，不會蓋掉星汐原本的資料；同一份備份搬兩次，已經搬過的會自動跳過。",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("① Orbis 備份檔", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "在 Orbis：設定 → 備份 →「檔案匯入匯出」→ 匯出備份（資料庫和檔案都要勾），得到一個 .zip。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy) {
                        Text(if (preview == null) "選擇備份 zip" else "換一個檔案")
                    }
                    preview?.let { p ->
                        Text("要搬哪些助手的東西？（全部都會變成時澈的）", style = MaterialTheme.typography.labelLarge)
                        p.assistants.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = a.id in selected, onCheckedChange = {
                                    selected = if (it) selected + a.id else selected - a.id
                                }, enabled = !busy)
                                Text("${a.name}　對話 ${a.conversations}・記憶 ${a.memories}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        val owners = selected
                        Toggle("對話（含壓縮前的原文）", conversations, !busy) { conversations = it }
                        Toggle("記憶筆記", memories, !busy) { memories = it }
                        Toggle("圖片和附件（${p.uploads} 個檔案）", files, !busy) { files = it }
                        Toggle("寵物" + if (p.petOwners.any { it in owners }) "" else "（沒找到）", pet, !busy) { pet = it }
                        Toggle("身體狀態" + if (p.bodyOwners.any { it in owners }) "" else "（沒找到）", body, !busy) { body = it }
                        Toggle("他的渴望" + if (p.desireOwners.any { it in owners }) "" else "（沒找到）", desires, !busy) { desires = it }
                        Toggle("共同空間・秘密基地・照片牆" + if (p.spaceOwners.any { it in owners }) "" else "（沒找到）", spaces, !busy) { spaces = it }
                        Toggle(if (p.mcpNames.isEmpty()) "MCP 工具（沒找到）" else "MCP 工具：" + p.mcpNames.joinToString("、"), mcp, !busy) { mcp = it }
                        Toggle("星汐已經有寵物／身體狀態時，用 Orbis 的覆蓋", replace, !busy) { replace = it }
                        Button(
                            onClick = {
                                busy = true; report = emptyList()
                                appScope.launch {
                                    runCatching {
                                        OrbisMigration.run(
                                            context,
                                            OrbisMigration.Options(selected, conversations, memories, files, pet, body, desires, spaces, mcp, replace),
                                        ) { status = it }
                                    }.onSuccess { report = it.lines; status = "搬好了！" }
                                        .onFailure { status = "搬到一半出錯：${it.message}" }
                                    busy = false
                                }
                            },
                            enabled = !busy && selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (busy) "搬家中…" else "開始搬家") }
                    }
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (report.isNotEmpty()) ReportCard(report)
                }
            }

            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("② 花園", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "花園不在整機備份裡。在 Orbis 花園右上角選單按「匯出花園備份」，得到一個 .json。\n" +
                            "日記 → 星汐日記、信件 → 信箱、錨點／心願／歌曲 → 寫進他的記憶。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { pickGarden.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text(if (garden == null) "選擇花園 json" else "換一個檔案")
                    }
                    garden?.let { g ->
                        Text(
                            "日記 ${g.count("DIARY")}・信件 ${g.count("LETTER")}・錨點 ${g.count("ANCHOR")}・心願 ${g.count("WISH")}・歌曲 ${g.count("SONG")}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (g.authors.isNotEmpty()) {
                            Text("哪個名字是他寫的？（其他算你寫的）", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                g.authors.forEach { name ->
                                    FilterChip(
                                        selected = name in hisNames,
                                        onClick = { hisNames = if (name in hisNames) hisNames - name else hisNames + name },
                                        label = { Text(name) },
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = {
                                appScope.launch {
                                    runCatching { OrbisMigration.importGarden(context, g, hisNames) }
                                        .onSuccess { gardenReport = it.lines }
                                        .onFailure { gardenReport = listOf("出錯了：${it.message}") }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("搬花園") }
                    }
                    if (gardenReport.isNotEmpty()) ReportCard(gardenReport)
                }
            }

            Text(
                "不會搬：Orbis 的設定、API key、主題（格式不同，key 請在星汐重新填）。學習紀錄目前星汐還沒有對應的地方。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ReportCard(lines: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
