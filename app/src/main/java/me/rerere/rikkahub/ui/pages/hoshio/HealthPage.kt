package me.rerere.rikkahub.ui.pages.hoshio

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import me.rerere.rikkahub.hoshio.care.CareWorker
import me.rerere.rikkahub.hoshio.health.HealthData
import me.rerere.rikkahub.hoshio.health.HealthPrefs

/**
 * 星汐「手表与健康」：授权 Health Connect、看今天的资料、设定用手表判断起床和心率关心。
 */
@Composable
fun HealthPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { HealthPrefs(context) }
    val callHim = rememberCallHim()
    val sdkStatus = remember { HealthConnectClient.getSdkStatus(context) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var summary by remember { mutableStateOf("讀取中…") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var watchWake by remember { mutableStateOf(prefs.watchWakeEnabled) }
    var heartAlert by remember { mutableStateOf(prefs.heartAlertEnabled) }
    var heartBpm by remember { mutableIntStateOf(prefs.heartAlertBpm) }
    var weekly by remember { mutableStateOf(prefs.weeklyReportEnabled) }

    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { result ->
        granted = result
        refreshKey++
    }

    LifecycleResumeEffect(Unit) {
        refreshKey++
        onPauseOrDispose { }
    }
    LaunchedEffect(refreshKey) {
        granted = HealthData.granted(context)
        summary = runCatching { HealthData.today(context) }.getOrElse { "讀取失敗：${it.message}" }
    }

    val readGranted = granted.count { it in HealthData.READ_PERMISSIONS }
    val backgroundGranted = HealthData.BACKGROUND_PERMISSION in granted

    PlayScaffold("手錶與健康") { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Pixel Watch 的資料會經由 Fitbit App 同步到 Health Connect，星汐再從那裡讀。" +
                    "要先在 Fitbit App 開啟「Health Connect 同步」。資料只在手機上讀，不會寫入或修改。",
                style = MaterialTheme.typography.bodyMedium,
            )

            when (sdkStatus) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (readGranted > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (readGranted > 0) "✅ 已授權 $readGranted / ${HealthData.READ_PERMISSIONS.size} 項" else "⚠️ 還沒授權",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (backgroundGranted) "背景讀取：已允許（App 沒開時也能偵測起床）" else "背景讀取：未允許（App 沒開時讀不到，起床偵測會不準）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = {
                                permissionLauncher.launch(HealthData.READ_PERMISSIONS + HealthData.BACKGROUND_PERMISSION)
                            }) { Text(if (readGranted > 0) "調整授權" else "授權讀取健康資料") }
                        }
                    }
                }

                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    Text("需要先安裝或更新 Health Connect", color = MaterialTheme.colorScheme.error)
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("去 Play 商店") }
                }

                else -> Text("這支手機不支援 Health Connect", color = MaterialTheme.colorScheme.error)
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("用手錶判斷起床", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "手錶的睡眠紀錄一同步過來就來說早安，還會知道你睡得好不好。資料還沒同步時，最多等 1 小時再改用螢幕亮起來判斷。需要開著「起床打招呼」",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = watchWake, onCheckedChange = { watchWake = it; prefs.watchWakeEnabled = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("心跳太快時關心你", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "沒在運動、步數很少，心率卻連續 20 分鐘平均超過 $heartBpm 時，他會來問問你；3 小時最多一次",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = heartAlert, onCheckedChange = {
                            heartAlert = it
                            prefs.heartAlertEnabled = it
                            CareWorker.sync(context)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("每週健康信", style = MaterialTheme.typography.titleSmall)
                            Text("每週日晚上 8 點後，他看你這週的睡眠、步數、心情，寫一封「這週的你」寄到信箱", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = weekly, onCheckedChange = { weekly = it; prefs.weeklyReportEnabled = it })
                    }
                    if (heartAlert) {
                        NumberChoiceChips(
                            options = listOf(100, 110, 120),
                            selected = heartBpm,
                            label = { "$it bpm" },
                            range = 80..200,
                            unit = "bpm",
                        ) { heartBpm = it; prefs.heartAlertBpm = it }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("今天的資料", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { refreshKey++ }) { Text("重新整理") }
                    }
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                    Text("（這是給他看的原始資料。沒資料時，打開 Fitbit App 讓它同步一下）", style = MaterialTheme.typography.labelSmall)
                }
            }

            FilledTonalButton(
                enabled = readGranted > 0,
                onClick = {
                    scope.launch {
                        callHim("健康", "She wants you to look at her health data today. Call her_health today (and sleep if useful), then talk to her about it in character.")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("請他看看我今天的狀況") }
        }
    }
}
