package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Route01
import me.rerere.rikkahub.data.ai.tools.local.currentLocationOrNull
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.hoshio.care.CarePlace
import me.rerere.rikkahub.hoshio.care.CarePrefs
import me.rerere.rikkahub.hoshio.care.CareWorker
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import kotlin.uuid.Uuid

/**
 * 星汐「地点提醒」：设定几个地点，到达或离开时让助手主动传讯息。
 */
@Composable
internal fun HoshioPlacesSection(assistant: Assistant) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val prefs = remember { CarePrefs(context) }
    var enabled by remember { mutableStateOf(prefs.placesEnabled) }
    var places by remember { mutableStateOf(prefs.places) }
    var showAddDialog by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }

    val locationPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.ACCESS_COARSE_LOCATION,
                displayName = { Text("大致位置") },
                usage = { Text("判斷你是否到達或離開設定的地點") },
                required = true
            ),
            PermissionInfo(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                displayName = { Text("精確位置") },
                usage = { Text("讓到達／離開的判斷更準確") },
                required = false
            ),
        )
    )
    PermissionManager(permissionState = locationPermissionState)

    fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 回传 true 表示权限都齐了 */
    fun ensurePermissions(): Boolean {
        if (!locationPermissionState.allRequiredPermissionsGranted) {
            locationPermissionState.requestPermissions()
            return false
        }
        if (!hasBackgroundLocation()) {
            toaster.show(message = "請在「權限 → 位置」選擇「一律允許」，星汐才能在背景判斷你到家或出門", type = ToastType.Warning)
            openAppSettings()
            return false
        }
        return true
    }

    fun setEnabled(value: Boolean) {
        if (value && !ensurePermissions()) return
        prefs.placesEnabled = value
        if (value) prefs.assistantId = assistant.id
        enabled = value
        CareWorker.sync(context)
    }

    CardGroup(title = { Text("地點提醒") }) {
        switchItem(
            checked = enabled,
            onCheckedChange = { setEnabled(it) },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Route01) },
            supportingContent = {
                Text("到達或離開下面的地點時，這個助手會主動傳訊息給你。大約每 15 分鐘檢查一次位置，所以可能會晚一點。需要定位「一律允許」")
            },
            headlineContent = { Text("到家／出門提醒") },
        )
        places.forEach { place ->
            item(
                leadingContent = { CardGroupIcon(icon = HugeIcons.Route01) },
                headlineContent = { Text(place.name) },
                supportingContent = { Text("半徑約 ${place.radiusMeters} 公尺") },
                trailingContent = {
                    IconButton(onClick = {
                        val updated = places.filterNot { it.id == place.id }
                        prefs.places = updated
                        prefs.placeStates = prefs.placeStates - place.id
                        places = updated
                    }) {
                        Icon(HugeIcons.Delete01, contentDescription = "刪除地點")
                    }
                },
            )
        }
        item(
            onClick = {
                if (ensurePermissions()) showAddDialog = true
            },
            leadingContent = { CardGroupIcon(icon = HugeIcons.Add01) },
            headlineContent = { Text(if (locating) "定位中…" else "把目前位置新增為地點") },
            supportingContent = { Text("人在家或公司時按這裡，取個名字就好") },
        )
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var radius by remember { mutableStateOf(CarePrefs.DEFAULT_PLACE_RADIUS) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增地點") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("會使用你現在的位置。")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(20) },
                        label = { Text("名稱，例如：家、公司") },
                        singleLine = true,
                    )
                    Text("範圍")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100, 200, 500).forEach { option ->
                            FilterChip(
                                selected = radius == option,
                                onClick = { radius = option },
                                label = { Text("$option 公尺") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !locating,
                    onClick = {
                        val placeName = name.trim()
                        val placeRadius = radius
                        showAddDialog = false
                        locating = true
                        scope.launch {
                            val location = context.currentLocationOrNull()
                            locating = false
                            if (location == null) {
                                toaster.show(message = "暫時拿不到位置，請確認定位已開啟後再試一次", type = ToastType.Error)
                                return@launch
                            }
                            val place = CarePlace(
                                id = Uuid.random().toString(),
                                name = placeName,
                                latitude = location.latitude,
                                longitude = location.longitude,
                                radiusMeters = placeRadius,
                            )
                            val updated = places + place
                            prefs.places = updated
                            places = updated
                            toaster.show(message = "已新增「$placeName」", type = ToastType.Success)
                        }
                    },
                ) { Text("新增") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
        )
    }
}
