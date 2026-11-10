package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Time02
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.hoshio.care.CarePrefs
import me.rerere.rikkahub.hoshio.care.CareWorker
import me.rerere.rikkahub.hoshio.screen.HoshioAccessibilityService
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType

/**
 * 星汐「螢幕观察」：开启无障碍服务后，AI 可以看一眼目前的螢幕；也可以设定定时看一眼。
 */
@Composable
internal fun HoshioScreenSection(assistant: Assistant, onUpdate: (Assistant) -> Unit) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val prefs = remember { CarePrefs(context) }
    var serviceEnabled by remember { mutableStateOf(HoshioAccessibilityService.isEnabledInSettings(context)) }
    var scheduled by remember { mutableStateOf(prefs.screenObservationEnabled) }
    var interval by remember { mutableStateOf(prefs.screenObservationIntervalMinutes) }
    var mode by remember { mutableStateOf(prefs.screenObservationMode) }
    var lockAllowed by remember { mutableStateOf(me.rerere.rikkahub.hoshio.screen.AppLock.allowed(context)) }
    val ignoredMode = mode == CarePrefs.SCREEN_MODE_IGNORED
    val toolEnabled = LocalToolOption.ScreenView in assistant.localTools

    // 从系统设定回来时更新无障碍服务状态
    LifecycleResumeEffect(Unit) {
        serviceEnabled = HoshioAccessibilityService.isEnabledInSettings(context)
        onPauseOrDispose { }
    }

    fun openAccessibilitySettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun setTool(enabled: Boolean) {
        val tools = if (enabled) {
            (assistant.localTools + LocalToolOption.ScreenView).distinct()
        } else {
            assistant.localTools - LocalToolOption.ScreenView
        }
        onUpdate(assistant.copy(localTools = tools))
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        CardGroup(title = { Text("螢幕觀察") }) {
            item(headlineContent = { Text("需要 Android 11 以上才能使用") })
        }
        return
    }

    CardGroup(title = { Text("螢幕觀察") }) {
        item(
            onClick = { openAccessibilitySettings() },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Settings03) },
            headlineContent = { Text(if (serviceEnabled) "無障礙服務：已開啟" else "無障礙服務：未開啟（點這裡設定）") },
            supportingContent = {
                Text(
                    "到「無障礙 → 已下載的應用程式 → 星汐」打開。只用來截圖和知道目前是哪個 App，不讀取畫面文字、不操作手機。" +
                        "如果開關是灰色的，先到「應用程式 → 星汐 → ⋮ → 允許受限制的設定」"
                )
            },
        )
        switchItem(
            checked = toolEnabled,
            onCheckedChange = {
                if (it && !serviceEnabled) {
                    toaster.show(message = "請先開啟星汐的無障礙服務", type = ToastType.Warning)
                    openAccessibilitySettings()
                }
                setTool(it)
            },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Eye) },
            supportingContent = { Text("你說「看一下我的螢幕」時，他可以截圖來看。需要模型支援圖片") },
            headlineContent = { Text("讓他看我的螢幕") },
        )
        switchItem(
            checked = lockAllowed && LocalToolOption.AppLock in assistant.localTools,
            onCheckedChange = {
                if (it && !serviceEnabled) {
                    toaster.show(message = "請先開啟星汐的無障礙服務", type = ToastType.Warning)
                    openAccessibilitySettings()
                }
                me.rerere.rikkahub.hoshio.screen.AppLock.setAllowed(context, it)
                lockAllowed = it
                val tools = if (it) (assistant.localTools + LocalToolOption.AppLock).distinct() else assistant.localTools - LocalToolOption.AppLock
                onUpdate(assistant.copy(localTools = tools))
            },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Settings03) },
            supportingContent = {
                Text(
                    "允許他在你不理他、一直滑別的 App 時，把你切回來，或暫時鎖住其他 App（最多 60 分鐘）。" +
                        "電話、簡訊、設定、鍵盤、相機和桌面永遠不會被鎖，通知欄隨時可以一鍵解除。"
                )
            },
            headlineContent = { Text("允許他把我切回來／鎖 App") },
        )
        switchItem(
            checked = scheduled,
            onCheckedChange = {
                if (it) {
                    if (!serviceEnabled) {
                        toaster.show(message = "請先開啟星汐的無障礙服務", type = ToastType.Warning)
                        openAccessibilitySettings()
                    }
                    if (!toolEnabled) setTool(true)
                    prefs.assistantId = assistant.id
                }
                prefs.screenObservationEnabled = it
                scheduled = it
                CareWorker.sync(context)
            },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Time02) },
            supportingContent = {
                Text("螢幕亮著、而且你不在星汐裡的時候偷看一眼，再主動傳訊息給你。每次都會呼叫一次 AI")
            },
            headlineContent = { Text("偷看螢幕") },
        )
        if (scheduled) {
            formItem(
                headlineContent = { Text("什麼時候偷看") },
                supportingContent = {
                    Text(
                        if (ignoredMode) {
                            "你超過下面的時間沒回他、螢幕卻亮著時，他會偷看你在忙什麼；一直沒回的話，之後每隔同樣時間最多再看一次"
                        } else {
                            "不管有沒有在聊天，固定每隔下面的時間偷看一次"
                        }
                    )
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        CarePrefs.SCREEN_MODE_IGNORED to "沒理他的時候",
                        CarePrefs.SCREEN_MODE_INTERVAL to "固定時間",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = mode == value,
                            onClick = {
                                mode = value
                                prefs.screenObservationMode = value
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
            formItem(headlineContent = { Text(if (ignoredMode) "沒回他多久" else "多久看一次") }) {
                me.rerere.rikkahub.ui.pages.hoshio.NumberChoiceChips(
                    options = listOf(30, 60, 120, 180),
                    selected = interval,
                    label = { m -> if (m < 60 || m % 60 != 0) "$m 分鐘" else "${m / 60} 小時" },
                    range = 15..1440,
                    unit = "分鐘",
                ) {
                    interval = it
                    prefs.screenObservationIntervalMinutes = it
                }
            }
        }
    }
}
