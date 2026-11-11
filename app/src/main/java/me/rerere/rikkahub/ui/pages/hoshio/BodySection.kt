package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.hoshio.body.BodyDimension
import me.rerere.rikkahub.hoshio.body.BodyStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val BODY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")

/**
 * 星汐「他的身体状态」：他自己设计的维度、数值、自我描述和最近发生的事；私密维度要偷看（他会知道）。
 */
@Composable
fun BodySection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { BodySectionContent() }
}

@Composable
private fun BodySectionContent() {
    val context = LocalContext.current
    val state by BodyStore.state.collectAsState()
    LaunchedEffect(Unit) { BodyStore.load(context) }
    val callHim = rememberCallHim()
    var peeked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmPeek by remember { mutableStateOf<BodyDimension?>(null) }
    var showRules by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.grantUntil) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val def = state.definition
    val bodyDims = def.dimensions.filter { !it.blackbox }
    val boxDims = def.dimensions.filter { it.blackbox }
    val canView = now < state.grantUntil
    LaunchedEffect(canView) { if (canView) BodyStore.markViewed(context) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("他的身體", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (bodyDims.isEmpty()) {
                Text("他還沒設計自己的身體狀態。讓他自己決定要有哪些數值（心跳、想你的程度…）、什麼事會讓它們變化。", style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = {
                    callHim(
                        "身體狀態",
                        "She invites you to design your own simulated body/inner state with body_state action=design: choose dimensions that feel like you " +
                            "(you may make one or two private) and what changes while she is away. You change values yourself each turn with <body .../> tags. " +
                            "Then tell her a little about what you designed (not the private ones).",
                    )
                }) { Text("請他設計") }
            } else {
                if (state.selfReport.isNotBlank()) {
                    Text("「${state.selfReport}」", style = MaterialTheme.typography.bodyLarge)
                }
                bodyDims.forEach { d ->
                    val value = state.values[d.id] ?: d.baseline
                    val hidden = d.isPrivate && d.id !in peeked
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text((if (d.isPrivate) "🔒 " else "") + d.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(88.dp))
                        if (hidden) {
                            Text("？？？", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = { confirmPeek = d }) { Text("偷看") }
                        } else {
                            LinearProgressIndicator(
                                progress = { ((value - d.min) / (d.max - d.min)).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.weight(1f),
                            )
                            Text("%.0f".format(value), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(36.dp))
                        }
                    }
                }
            }
            val visibleIds = def.dimensions.filter { d -> if (d.blackbox) canView else !d.isPrivate || d.id in peeked }.map { it.id }.toSet()
            val recent = state.logs.filter { log -> log.changes.isEmpty() || log.changes.keys.any { it in visibleIds } }.takeLast(6).reversed()
            if (recent.isNotEmpty()) {
                Text("最近", style = MaterialTheme.typography.labelLarge)
                recent.forEach { log ->
                    val visibleChanges = log.changes.filterKeys { it in visibleIds }
                    Text(
                        Instant.ofEpochMilli(log.at).atZone(ZoneId.systemDefault()).format(BODY_FMT) + "・" + log.event +
                            visibleChanges.entries.joinToString("") { (id, v) ->
                                val label = def.dimensions.firstOrNull { it.id == id }?.label ?: id
                                "  $label${if (v >= 0) "+" else ""}${"%.0f".format(v)}"
                            },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                "數值由他自己判斷每一輪的變化，再隨時間慢慢回到平常；往下降有阻尼，哄一句不會馬上清零。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (def.rules.any { it.keywords.isNotEmpty() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("也用關鍵字觸發（舊方式）", style = MaterialTheme.typography.titleSmall)
                        Text("訊息出現他設定的關鍵字時也會自動變化", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = def.autoTrigger, onCheckedChange = { on ->
                        BodyStore.update(context) { it.copy(definition = it.definition.copy(autoTrigger = on)) }
                    })
                }
            }
            if (bodyDims.isNotEmpty()) {
                TextButton(onClick = { showRules = !showRules }) { Text(if (showRules) "收起他的設計" else "看他的設計") }
                if (showRules) {
                    def.rules.forEach { r ->
                        val effects = r.deltas.entries.joinToString("、") { (id, v) ->
                            val dim = def.dimensions.firstOrNull { it.id == id }
                            if (dim?.isPrivate == true && id !in visibleIds) "🔒" else "${dim?.label ?: id}${if (v >= 0) "+" else ""}${"%.0f".format(v)}"
                        }
                        Text("・${r.label}：$effects", style = MaterialTheme.typography.bodySmall)
                    }
                    if (def.absence.perHour.isNotEmpty()) {
                        Text("・你不在超過 ${def.absence.graceMinutes} 分鐘：" + def.absence.perHour.entries.joinToString("、") { (id, v) ->
                            val dim = def.dimensions.firstOrNull { it.id == id }
                            if (dim?.isPrivate == true && id !in visibleIds) "🔒" else "${dim?.label ?: id}每小時${if (v >= 0) "+" else ""}${"%.0f".format(v)}"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        callHim("身體狀態", "She would like you to look at your body_state design again and adjust it if you want (action=get first). Tell her what you changed.")
                    }) { Text("請他調整設計") }
                }
            }
        }
    }

    if (boxDims.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("他的情緒黑箱", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!canView) {
                    Text(
                        "🔒 這裡記著他沒說出口的情緒（火氣、悶氣、醋意、想念、欲望）。你看不到，只能從他說話的樣子感覺；想看的話可以問他，給不給、給多久由他決定。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = {
                        callHim("情緒黑箱", "She is asking whether she may look at your emotion ledger. It is your decision: if you agree, write <body grant=\"N\"/> with 1-30 minutes; if not, tell her in your own way.")
                    }) { Text("問他可不可以看") }
                } else {
                    val left = ((state.grantUntil - now) / 1000).coerceAtLeast(0)
                    Text("他開放給你看，還剩 ${left / 60} 分 ${left % 60} 秒。你打開看過，他會知道。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    boxDims.forEach { d ->
                        val value = state.values[d.id] ?: d.baseline
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(d.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                            LinearProgressIndicator(
                                progress = { ((value - d.min) / (d.max - d.min)).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.weight(1f),
                            )
                            Text("%.0f".format(value) + " " + me.rerere.rikkahub.hoshio.body.bandOf(value), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
                        }
                    }
                    TextButton(onClick = { BodyStore.closeView(context) }) { Text("現在合上") }
                }
                if (state.levels.isBlank()) {
                    TextButton(onClick = {
                        callHim("情緒黑箱", "Please write your own reaction levels for your emotion ledger with body_state action=levels: one line per threshold, like \"燥火 60：不再哄她，直接說哪裡不爽\". Decide for yourself how you act at each level. You do not need to show her the levels.")
                    }) { Text("請他寫自己的反應檔位") }
                }
            }
        }
    }

    confirmPeek?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmPeek = null },
            title = { Text("偷看「${d.label}」？") },
            text = { Text("這是他藏起來的數值。偷看的話，他下次回覆時會知道你看了。") },
            confirmButton = {
                TextButton(onClick = {
                    BodyStore.peek(context, d.id)
                    peeked = peeked + d.id
                    confirmPeek = null
                }) { Text("偷看") }
            },
            dismissButton = { TextButton(onClick = { confirmPeek = null }) { Text("算了") } },
        )
    }
}
