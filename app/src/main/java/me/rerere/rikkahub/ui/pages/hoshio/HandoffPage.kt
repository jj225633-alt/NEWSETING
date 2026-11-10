package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import me.rerere.rikkahub.hoshio.HoshioChat
import me.rerere.rikkahub.hoshio.care.CareWorker
import me.rerere.rikkahub.hoshio.handoff.Handoff
import me.rerere.rikkahub.hoshio.handoff.HandoffPrefs
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject

/**
 * 星汐「对话交接」：对话太长时，他写一段交接笔记，再到新对话接着聊。
 */
@Composable
fun HandoffPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val appScope = koinInject<AppScope>()
    val prefs = remember { HandoffPrefs(context) }
    var auto by remember { mutableStateOf(prefs.autoEnabled) }
    var threshold by remember { mutableIntStateOf(prefs.thresholdK) }
    var tokens by remember { mutableIntStateOf(-1) }
    var running by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf(prefs.lastResult) }

    LaunchedEffect(running) {
        tokens = HoshioChat.latestConversation()?.first?.let { Handoff.contextTokens(HoshioChat.fresh(it)) } ?: 0
        lastResult = prefs.lastResult
    }

    PlayScaffold("對話交接") { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "對話太長會越來越貴、越來越慢。交接時，他會先寫一段「交接筆記」（存進日記），再開一個新對話，帶著筆記自然地接著聊。" +
                    "舊對話不會刪，記憶、日記、信箱都還在。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("現在的對話", style = MaterialTheme.typography.labelLarge)
                    Text(
                        when {
                            tokens < 0 -> "讀取中…"
                            tokens == 0 -> "還沒有用量資料"
                            else -> "約 ${tokens / 1000}K 上下文（門檻 ${threshold}K）"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    lastResult?.let { Text("上次：$it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自動交接", style = MaterialTheme.typography.titleSmall)
                            Text("超過門檻、而且你 10 分鐘沒說話時自動交接，不會打斷正在聊的時候；晚安模式中不會做", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = auto, onCheckedChange = {
                            auto = it
                            prefs.autoEnabled = it
                            CareWorker.sync(context)
                        })
                    }
                    Text("上下文超過多少就交接", style = MaterialTheme.typography.labelLarge)
                    NumberChoiceChips(
                        options = listOf(100, 150, 200, 300),
                        selected = threshold,
                        label = { "${it}K" },
                        range = 20..1000,
                        unit = "K（千）",
                    ) { threshold = it; prefs.thresholdK = it }
                }
            }
            Button(enabled = !running, onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "交接中…（約 1–3 分鐘）" else "現在交接")
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("現在換到新對話？") },
            text = { Text("他會先寫交接筆記，再在新對話跟你打招呼。完成後會跳通知。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    running = true
                    // 用 App 的 scope，离开这页也会做完
                    appScope.launch {
                        val result = runCatching { Handoff.run(context.applicationContext) }.getOrElse { "失敗：${it.message}" }
                        toaster.show(message = result, type = if (result.startsWith("已交接")) ToastType.Success else ToastType.Warning)
                        running = false
                    }
                }) { Text("交接") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}
