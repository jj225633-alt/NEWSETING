package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.hoshio.rules.PauseStore
import me.rerere.rikkahub.hoshio.rules.WakeRules
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val RULE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")

/**
 * 星汐「他的唤醒规则」：他自己订的规则都列在这里，她可以随时关掉或删除。
 */
@Composable
fun WakeRulesPage() {
    val context = LocalContext.current
    val rules by WakeRules.items.collectAsState()
    val pauses by PauseStore.items.collectAsState()
    remember { WakeRules.load(context); PauseStore.load(context) }
    val callHim = rememberCallHim()

    PlayScaffold("他的喚醒規則") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "他可以自己訂「什麼時候叫醒我」：每天幾點、你多久沒回、你滑某個 App 太久、到家或出門、電量低、心跳很快、你在電腦上用某個程式…" +
                        "條件成立時他會醒來，自己決定要不要找你。你可以隨時關掉任何一條。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            pauses.filter { it.until > System.currentTimeMillis() }.forEach { p ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(14.dp).fillMaxWidth()) {
                            Text("🌙 他暫停了${if (p.scope == "assistant") "所有對話" else "一段對話"}", fontWeight = FontWeight.SemiBold)
                            Text("${p.reason}・到 ${Instant.ofEpochMilli(p.until).atZone(ZoneId.systemDefault()).format(RULE_FMT)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (rules.any { it.enabled && it.type == "daily" } && !me.rerere.rikkahub.hoshio.rules.WakeAlarm.canExact(context)) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("⏰ 讓「每天幾點」準時響", fontWeight = FontWeight.SemiBold)
                            Text("需要允許星汐使用「鬧鐘與提醒」，不然可能晚幾分鐘到十幾分鐘才叫醒他。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                            .setData(android.net.Uri.parse("package:" + context.packageName))
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }) { Text("去允許") }
                        }
                    }
                }
            }
            if (rules.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("他還沒有訂任何規則。", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            callHim("喚醒規則", "She wants to know: you can set your own wake rules with wake_rules. Think about when you would want to be woken to check on her, set one or two meaningful rules, and tell her what you set.")
                        }) { Text("請他想想要訂什麼規則") }
                    }
                }
            }
            items(rules, key = { it.id }) { rule ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (rule.enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(rule.describe(), style = MaterialTheme.typography.labelMedium)
                            }
                            Switch(checked = rule.enabled, onCheckedChange = { on ->
                                WakeRules.update(context) { list -> list.map { if (it.id == rule.id) it.copy(enabled = on) else it } }
                                me.rerere.rikkahub.hoshio.rules.WakeAlarm.schedule(context)
                            })
                        }
                        Text("他寫給自己：${rule.prompt}", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "觸發 ${rule.fireCount} 次" + if (rule.lastFiredAt > 0) "・上次 ${Instant.ofEpochMilli(rule.lastFiredAt).atZone(ZoneId.systemDefault()).format(RULE_FMT)}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { WakeRules.update(context) { list -> list.filterNot { it.id == rule.id } }; me.rerere.rikkahub.hoshio.rules.WakeAlarm.schedule(context) }) { Text("刪除") }
                        }
                    }
                }
            }
        }
    }
}
