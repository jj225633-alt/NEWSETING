package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.ClipboardPaste
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.DashboardSpeed02
import me.rerere.hugeicons.stroke.JavaScript
import me.rerere.hugeicons.stroke.MessageQuestion
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.hugeicons.stroke.Route01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.hoshio.care.CarePrefs
import me.rerere.rikkahub.hoshio.care.CareWorker
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            innerPadding = innerPadding,
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.READ_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                required = true
            ),
            PermissionInfo(
                permission = Manifest.permission.WRITE_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                required = true
            ),
        )
    )
    PermissionManager(permissionState = calendarPermissionState)

    // 星汐: 定位工具。大致位置为必需，精确位置可选（用户可以只给大致位置）
    val locationPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.ACCESS_COARSE_LOCATION,
                displayName = { Text("大致位置") },
                usage = { Text("讓 AI 在你詢問時知道你大概在哪個區域") },
                required = true
            ),
            PermissionInfo(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                displayName = { Text("精確位置") },
                usage = { Text("讓位置更準確（可不允許，只用大致位置）") },
                required = false
            ),
        )
    )
    PermissionManager(permissionState = locationPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        if (enabled && option == LocalToolOption.Location && !locationPermissionState.allRequiredPermissionsGranted) {
            locationPermissionState.requestPermissions()
            return
        }
        val newLocalTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    fun CardGroupScope.toolItem(
        option: LocalToolOption,
        icon: ImageVector,
        @StringRes title: Int,
        @StringRes description: Int,
    ) {
        val checked = assistant.localTools.contains(option)
        switchItem(
            checked = checked,
            onCheckedChange = { toggleLocalTool(option, it) },
            // 图标底色跟着开关状态变化
            leadingContent = {
                CardGroupIcon(
                    icon = icon,
                    containerColor = if (checked) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (checked) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            supportingContent = { Text(stringResource(description)) },
            headlineContent = { Text(stringResource(title)) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .padding(bottom = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            toolItem(
                option = LocalToolOption.JavascriptEngine,
                icon = HugeIcons.JavaScript,
                title = R.string.assistant_page_local_tools_javascript_engine_title,
                description = R.string.assistant_page_local_tools_javascript_engine_desc,
            )
            toolItem(
                option = LocalToolOption.TimeInfo,
                icon = HugeIcons.Clock01,
                title = R.string.assistant_page_local_tools_time_info_title,
                description = R.string.assistant_page_local_tools_time_info_desc,
            )
            toolItem(
                option = LocalToolOption.Clipboard,
                icon = HugeIcons.ClipboardPaste,
                title = R.string.assistant_page_local_tools_clipboard_title,
                description = R.string.assistant_page_local_tools_clipboard_desc,
            )
            toolItem(
                option = LocalToolOption.Tts,
                icon = HugeIcons.VolumeHigh,
                title = R.string.assistant_page_local_tools_tts_title,
                description = R.string.assistant_page_local_tools_tts_desc,
            )
            toolItem(
                option = LocalToolOption.AskUser,
                icon = HugeIcons.MessageQuestion,
                title = R.string.assistant_page_local_tools_ask_user_title,
                description = R.string.assistant_page_local_tools_ask_user_desc,
            )
            toolItem(
                option = LocalToolOption.ScreenTime,
                icon = HugeIcons.SmartPhone01,
                title = R.string.assistant_page_local_tools_screen_time_title,
                description = R.string.assistant_page_local_tools_screen_time_desc,
            )
            toolItem(
                option = LocalToolOption.Calendar,
                icon = HugeIcons.Calendar03,
                title = R.string.assistant_page_local_tools_calendar_title,
                description = R.string.assistant_page_local_tools_calendar_desc,
            )
            toolItem(
                option = LocalToolOption.ChartDisplay,
                icon = HugeIcons.ChartLineData01,
                title = R.string.assistant_page_local_tools_chart_display_title,
                description = R.string.assistant_page_local_tools_chart_display_desc,
            )
            toolItem(
                option = LocalToolOption.DeviceStatus,
                icon = HugeIcons.DashboardSpeed02,
                title = R.string.assistant_page_local_tools_device_status_title,
                description = R.string.assistant_page_local_tools_device_status_desc,
            )
            toolItem(
                option = LocalToolOption.Location,
                icon = HugeIcons.Route01,
                title = R.string.assistant_page_local_tools_location_title,
                description = R.string.assistant_page_local_tools_location_desc,
            )
        }

        HoshioCareSection(assistant = assistant)
    }
}

/**
 * 星汐「主动关心」：在背景检查手机状态，需要时让这个助手主动传讯息。
 */
@Composable
private fun HoshioCareSection(assistant: Assistant) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val prefs = remember { CarePrefs(context) }
    var lowBattery by remember { mutableStateOf(prefs.lowBatteryEnabled) }
    var nightUsage by remember { mutableStateOf(prefs.nightUsageEnabled) }
    var wakeUp by remember { mutableStateOf(prefs.wakeUpEnabled) }
    var ownerId by remember { mutableStateOf(prefs.assistantId) }
    var lastResult by remember { mutableStateOf(prefs.lastResult) }

    val notificationPermissionState = rememberPermissionState(
        permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            setOf(
                PermissionInfo(
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    displayName = { Text("通知") },
                    usage = { Text("AI 主動關心你時，用通知把訊息送到你的手機") },
                    required = true
                )
            )
        } else {
            emptySet()
        }
    )
    PermissionManager(permissionState = notificationPermissionState)

    fun update(lowBatteryValue: Boolean, nightUsageValue: Boolean, wakeUpValue: Boolean) {
        val any = lowBatteryValue || nightUsageValue || wakeUpValue
        if (any && !notificationPermissionState.allRequiredPermissionsGranted) {
            notificationPermissionState.requestPermissions()
        }
        if (wakeUpValue && !wakeUp && !context.hasUsageStatsPermission()) {
            toaster.show(message = "起床問候需要「使用情況存取」權限，請在接下來的頁面允許星汐", type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        prefs.lowBatteryEnabled = lowBatteryValue
        prefs.nightUsageEnabled = nightUsageValue
        prefs.wakeUpEnabled = wakeUpValue
        if (any) {
            prefs.assistantId = assistant.id
            ownerId = assistant.id
        }
        lowBattery = lowBatteryValue
        nightUsage = nightUsageValue
        wakeUp = wakeUpValue
        CareWorker.sync(context)
    }

    val ownedByOther = ownerId != null && ownerId != assistant.id && (lowBattery || nightUsage || wakeUp)

    CardGroup(title = { Text("主動關心") }) {
        item(
            headlineContent = { Text("讓這個助手主動找你") },
            supportingContent = {
                Text(
                    buildString {
                        append("大約每 15 分鐘在背景檢查一次手機，遇到下面的情況，AI 會在你和它最近的對話裡主動回一句話，並用通知告訴你。")
                        if (ownedByOther) append("\n目前由另一個助手負責；在這裡開啟會改成由這個助手負責。")
                        lastResult?.let { append("\n最近一次：").append(it) }
                    }
                )
            },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Notification01) },
        )
        switchItem(
            checked = lowBattery && !ownedByOther,
            onCheckedChange = { update(it, nightUsage, wakeUp) },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Zap) },
            supportingContent = { Text("電量低於 ${CarePrefs.LOW_BATTERY_PERCENT}% 且沒在充電時提醒你充電；充電後才會再提醒") },
            headlineContent = { Text("電量低") },
        )
        switchItem(
            checked = nightUsage && !ownedByOther,
            onCheckedChange = { update(lowBattery, it, wakeUp) },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Moon02) },
            supportingContent = { Text("凌晨 0:00–5:00 連續亮著螢幕超過約 ${CarePrefs.NIGHT_USAGE_MINUTES} 分鐘時，提醒你休息；一晚最多一次") },
            headlineContent = { Text("深夜還在滑手機") },
        )
        switchItem(
            checked = wakeUp && !ownedByOther,
            onCheckedChange = { update(lowBattery, nightUsage, it) },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Sun01) },
            supportingContent = {
                Text(
                    "螢幕關了 ${CarePrefs.WAKE_MIN_SLEEP_HOURS} 小時以上、早上（${CarePrefs.WAKE_START_HOUR}:00–${CarePrefs.WAKE_END_HOUR}:00）第一次打開手機時，" +
                        "當作你起床了，跟你說早安；一天一次，可能晚 15–30 分鐘。需要「使用情況存取」權限"
                )
            },
            headlineContent = { Text("起床打招呼") },
        )
        item(
            onClick = {
                if (!notificationPermissionState.allRequiredPermissionsGranted) {
                    notificationPermissionState.requestPermissions()
                }
                prefs.assistantId = assistant.id
                ownerId = assistant.id
                CareWorker.runTest(context)
                lastResult = "測試中…（約 1 分鐘內會收到通知）"
                prefs.lastResult = lastResult
                toaster.show(message = "已送出測試，AI 回覆後會跳出通知", type = ToastType.Success)
            },
            headlineContent = { Text("立刻測試一次") },
            supportingContent = { Text("讓這個助手馬上主動傳一則訊息給你。請先和它至少聊過一句") },
        )
    }
}
