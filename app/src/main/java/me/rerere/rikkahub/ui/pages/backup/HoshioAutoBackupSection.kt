package me.rerere.rikkahub.ui.pages.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.hoshio.backup.AutoBackupPrefs
import me.rerere.rikkahub.hoshio.backup.AutoBackupWorker
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType

/**
 * 星汐「自动备份」：定期备份到下面 WebDAV／S3 分页设定好的位置。
 */
@Composable
internal fun HoshioAutoBackupSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val prefs = remember { AutoBackupPrefs(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var interval by remember { mutableStateOf(prefs.intervalHours) }
    var keep by remember { mutableStateOf(prefs.keepCount) }
    val lastResult = remember { prefs.lastResult }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自動備份", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "定期把對話、設定、記憶和日記備份到下面 WebDAV（優先）或 S3 設定好的位置。需要網路，電量太低時會延後。" +
                            (lastResult?.let { "\n最近一次：$it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        prefs.enabled = it
                        enabled = it
                        AutoBackupWorker.sync(context)
                    },
                )
            }
            if (enabled) {
                Text("多久備份一次", style = MaterialTheme.typography.labelLarge)
                me.rerere.rikkahub.ui.pages.hoshio.NumberChoiceChips(
                    options = listOf(12, 24, 72, 168),
                    selected = interval,
                    label = { hours -> if (hours % 24 != 0) "$hours 小時" else if (hours == 168) "每週" else "${hours / 24} 天" },
                    range = 1..720,
                    unit = "小時",
                ) {
                    interval = it
                    prefs.intervalHours = it
                    AutoBackupWorker.sync(context)
                }
                Text("雲端保留幾份", style = MaterialTheme.typography.labelLarge)
                me.rerere.rikkahub.ui.pages.hoshio.NumberChoiceChips(
                    options = listOf(3, 7, 14, 30),
                    selected = keep,
                    label = { "$it 份" },
                    range = 1..100,
                    unit = "份",
                ) { keep = it; prefs.keepCount = it }
            }
            TextButton(onClick = {
                AutoBackupWorker.runNow(context)
                toaster.show(message = "開始備份，完成後這裡會顯示結果", type = ToastType.Success)
            }) { Text("現在備份一次") }
        }
    }
}
