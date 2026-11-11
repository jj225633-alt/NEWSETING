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
    val context = LocalContext.current
    val state by BodyStore.state.collectAsState()
    LaunchedEffect(Unit) { BodyStore.load(context) }
    val callHim = rememberCallHim()
    var peeked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmPeek by remember { mutableStateOf<BodyDimension?>(null) }
    var showRules by remember { mutableStateOf(false) }
    val def = state.definition

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("他的身體", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (def.dimensions.isEmpty()) {
                Text("他還沒設計自己的身體狀態。讓他自己決定要有哪些數值（心跳、想你的程度…）、什麼事會讓它們變化。", style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = {
                    callHim(
                        "身體狀態",
                        "She invites you to design your own simulated body/inner state with body_state action=design: choose dimensions that feel like you " +
                            "(you may make one or two private), rules with keywords from how she talks to you, what changes while she is away, and calming words. " +
                            "Then tell her a little about what you designed (not the private ones).",
                    )
                }) { Text("請他設計") }
                return@Column
            }
            if (state.selfReport.isNotBlank()) {
                Text("「${state.selfReport}」", style = MaterialTheme.typography.bodyLarge)
            }
            def.dimensions.forEach { d ->
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
            val recent = state.logs.takeLast(6).reversed()
            if (recent.isNotEmpty()) {
                Text("最近", style = MaterialTheme.typography.labelLarge)
                recent.forEach { log ->
                    val visibleChanges = log.changes.filterKeys { id -> def.dimensions.firstOrNull { it.id == id }?.let { !it.isPrivate || id in peeked } ?: false }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("你的話會自動觸發", style = MaterialTheme.typography.titleSmall)
                    Text("訊息裡出現他設定的關鍵字時，數值會跟著變（在手機上比對，不花額外費用）", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = def.autoTrigger, onCheckedChange = { on ->
                    BodyStore.update(context) { it.copy(definition = it.definition.copy(autoTrigger = on)) }
                })
            }
            TextButton(onClick = { showRules = !showRules }) { Text(if (showRules) "收起他的規則" else "看他的規則（${def.rules.size} 條）") }
            if (showRules) {
                def.rules.forEach { r ->
                    val effects = r.deltas.entries.joinToString("、") { (id, v) ->
                        val dim = def.dimensions.firstOrNull { it.id == id }
                        if (dim?.isPrivate == true && id !in peeked) "🔒" else "${dim?.label ?: id}${if (v >= 0) "+" else ""}${"%.0f".format(v)}"
                    }
                    Text(
                        "・${r.label}：$effects" + if (r.keywords.isNotEmpty()) "（${r.keywords.joinToString("、")}）" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (def.absence.perHour.isNotEmpty()) {
                    Text("・你不在超過 ${def.absence.graceMinutes} 分鐘：" + def.absence.perHour.entries.joinToString("、") { (id, v) ->
                        val dim = def.dimensions.firstOrNull { it.id == id }
                        if (dim?.isPrivate == true && id !in peeked) "🔒" else "${dim?.label ?: id}每小時${if (v >= 0) "+" else ""}${"%.0f".format(v)}"
                    }, style = MaterialTheme.typography.bodySmall)
                }
                if (def.cooling.keywords.isNotEmpty()) {
                    Text("・冷靜詞：${def.cooling.keywords.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    callHim("身體狀態", "She would like you to look at your body_state design again and adjust it if you want (action=get first). Tell her what you changed.")
                }) { Text("請他調整設計") }
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
