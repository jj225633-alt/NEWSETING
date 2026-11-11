package me.rerere.rikkahub.ui.pages.hoshio

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.hoshio.voice.VoicePrefs
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings

/**
 * 星汐「语音」：打给他、他能不能打给你、他的讯息要不要念出来。
 */
@Composable
fun VoiceSettingsPage() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val prefs = remember { VoicePrefs(context) }
    var allowIncoming by remember { mutableStateOf(prefs.allowIncoming) }
    var cooldown by remember { mutableIntStateOf(prefs.rejectCooldownMinutes) }
    var readAloud by remember { mutableStateOf(prefs.readAloud) }
    var readLocked by remember { mutableStateOf(prefs.readWhenLocked) }
    val assistant = settings.assistants.firstOrNull { it.id == settings.assistantId }
    val voiceToolOn = assistant?.localTools?.contains(LocalToolOption.VoiceMessage) == true

    @Composable
    fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    PlayScaffold("語音") { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Button(onClick = { navController.navigate(Screen.VoiceCall()) }, modifier = Modifier.fillMaxWidth()) { Text("📞 打電話給他") }
            Text(
                "通話時用手機內建的語音辨識聽你說話，他用你在「設定 → 語音服務」選的聲音回答。通話內容會照常留在聊天紀錄裡。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!voiceToolOn) {
                Card(
                    onClick = { assistant?.let { navController.navigate(Screen.AssistantLocalTool(it.id.toString())) } },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        "他還不能傳語音訊息或打給你：到「本地工具」打開「語音訊息」（點這裡）",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchRow("讓他打電話給你", "他想聽你的聲音時會打來，手機會響；可以接或拒接", allowIncoming) {
                        allowIncoming = it; prefs.allowIncoming = it
                    }
                    if (allowIncoming) {
                        Text("拒接後多久內不能再打", style = MaterialTheme.typography.labelLarge)
                        NumberChoiceChips(listOf(15, 30, 60, 180), cooldown, { "$it 分鐘" }, 5..1440, "分鐘") {
                            cooldown = it; prefs.rejectCooldownMinutes = it
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchRow("他主動找你時念出來", "主動關心、約好的時間、喚醒規則傳來的訊息，會用他的聲音念一次", readAloud) {
                        readAloud = it; prefs.readAloud = it
                    }
                    if (readAloud) {
                        SwitchRow("鎖屏時也念", "關掉的話只有螢幕亮著時才念", readLocked) {
                            readLocked = it; prefs.readWhenLocked = it
                        }
                    }
                }
            }
            TextButton(onClick = { navController.navigate(Screen.SettingSpeech) }) { Text("設定語音服務（他的聲音）") }
        }
    }
}
