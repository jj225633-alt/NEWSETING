package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.hoshio.care.CarePrefs
import me.rerere.rikkahub.hoshio.care.CareWorker
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem

/**
 * 星汐「自由时间」：她很久没理他时，他可以自己用工具去探索，再传讯息分享。
 */
@Composable
internal fun HoshioFreeTimeSection(assistant: Assistant) {
    val context = LocalContext.current
    val prefs = remember { CarePrefs(context) }
    var enabled by remember { mutableStateOf(prefs.freeTimeEnabled) }
    var idle by remember { mutableStateOf(prefs.freeTimeIdleMinutes) }

    CardGroup(title = { Text("自由時間") }) {
        switchItem(
            checked = enabled,
            onCheckedChange = {
                prefs.freeTimeEnabled = it
                if (it) prefs.assistantId = assistant.id
                enabled = it
                CareWorker.sync(context)
            },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Sparkles) },
            supportingContent = {
                Text(
                    "你很久沒理他時，他會自己用能用的工具（網路搜尋、MCP、記憶等）去看看感興趣的東西，再傳訊息跟你分享。" +
                        "早上 ${CarePrefs.FREE_TIME_QUIET_END_HOUR} 點前不打擾，一天最多 ${CarePrefs.FREE_TIME_MAX_PER_DAY} 次，每次都會呼叫 AI 好幾次"
                )
            },
            headlineContent = { Text("沒理他時，讓他自己探索") },
        )
        if (enabled) {
            formItem(
                headlineContent = { Text("沒回他多久") },
                supportingContent = {
                    Text("一直沒回的話，之後每隔同樣時間最多再探索一次。要用搜尋或 MCP，記得在聊天頁打開它們；需要你核准的 MCP 工具，他自己用不了")
                },
            ) {
                me.rerere.rikkahub.ui.pages.hoshio.NumberChoiceChips(
                    options = listOf(60, 120, 180, 360),
                    selected = idle,
                    label = { m -> if (m < 60 || m % 60 != 0) "$m 分鐘" else "${m / 60} 小時" },
                    range = 15..1440,
                    unit = "分鐘",
                ) {
                    idle = it
                    prefs.freeTimeIdleMinutes = it
                }
            }
        }
    }
}

/**
 * 星汐「对话跟随助手」：修改助手的模型、搜寻、MCP 等设定时，套用到所有对话。
 */
@Composable
internal fun HoshioConversationSection() {
    var follow by remember { mutableStateOf(me.rerere.rikkahub.hoshio.HoshioFlags.followAssistant) }
    CardGroup(title = { Text("對話設定") }) {
        switchItem(
            checked = follow,
            onCheckedChange = {
                me.rerere.rikkahub.hoshio.HoshioFlags.setFollowAssistant(it)
                follow = it
            },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Sparkles) },
            supportingContent = {
                Text(
                    "開啟時，所有對話都使用助手目前的模型、推理、搜尋、MCP、工作區和技能；在聊天頁切換這些設定也會直接改到助手，套用到所有對話。" +
                        "關閉時恢復 RikkaHub 原本的做法：每個對話開始後固定自己的設定"
                )
            },
            headlineContent = { Text("所有對話跟隨助手設定") },
        )
    }
}
