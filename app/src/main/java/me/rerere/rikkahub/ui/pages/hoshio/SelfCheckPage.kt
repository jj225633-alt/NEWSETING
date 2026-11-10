package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import me.rerere.rikkahub.hoshio.SelfCheck
import me.rerere.rikkahub.hoshio.care.CarePrefs
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 自我检查：哪个权限或设定没开好、影响哪个功能、怎么修。
 */
@Composable
fun SelfCheckPage() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val items = remember(refresh) { SelfCheck.run(context) }
    val lastResult = remember(refresh) { CarePrefs(context).lastResult }

    Scaffold(
        topBar = { TopAppBar(title = { Text("自我檢查") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val bad = items.count { !it.ok }
                Text(
                    if (bad == 0) "全部都設定好了 ✨" else "有 $bad 項沒開好，對應的功能可能不會動。點「去設定」可以直接修。",
                    style = MaterialTheme.typography.titleMedium,
                )
                lastResult?.let { Text("主動關心最近一次：$it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                Text("也可以直接問他「幫我檢查一下為什麼 XX 沒反應」。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            items(items, key = { it.title }) { item ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (item.ok) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer,
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text((if (item.ok) "✅ " else "⚠️ ") + item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("用在：${item.neededFor}", style = MaterialTheme.typography.bodySmall)
                            if (!item.ok) Text(item.fix, style = MaterialTheme.typography.bodySmall)
                        }
                        val intent = item.settingsIntent
                        if (!item.ok && intent != null) {
                            TextButton(onClick = {
                                runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }) { Text("去設定") }
                        }
                    }
                }
            }
        }
    }
}
