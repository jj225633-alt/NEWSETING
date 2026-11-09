package me.rerere.rikkahub.data.ai.tools.local

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 星汐: 让 AI 读取手机当前状态（电量、充电、网络、静音、省电等）。
 * 全部是系统公开状态，不需要额外的运行时权限。
 */
internal fun buildDeviceStatusTool(context: Context): Tool = Tool(
    name = "get_device_status",
    description = """
        Get the current status of the user's phone: battery level and charging state, power saving mode,
        network connection type, ringer mode (normal/vibrate/silent), do-not-disturb, whether the screen is on,
        and time since the last boot. Use this to understand the user's situation (e.g. low battery, offline,
        phone on silent) and respond with care.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val payload = buildJsonObject {
            // 电量：读取粘性广播，不需要权限
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (battery != null) {
                val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) put("battery_percent", level * 100 / scale)
                val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                put(
                    "battery_status",
                    when (status) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                        BatteryManager.BATTERY_STATUS_FULL -> "full"
                        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                        else -> "unknown"
                    }
                )
                put(
                    "plugged",
                    when (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                        BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
                        0 -> "unplugged"
                        else -> "unknown"
                    }
                )
                val temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (temperature != Int.MIN_VALUE) put("battery_temperature_celsius", temperature / 10.0)
            }

            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power != null) {
                put("power_save_mode", power.isPowerSaveMode)
                put("screen_on", power.isInteractive)
            }

            // 网络：只看连接类型，不读取 Wi-Fi 名称等隐私信息
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val capabilities = runCatching {
                connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            }.getOrNull()
            put(
                "network",
                when {
                    capabilities == null -> "offline"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
            )
            if (capabilities != null) {
                put("vpn", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                put("internet_validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }

            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audio != null) {
                put(
                    "ringer_mode",
                    when (audio.ringerMode) {
                        AudioManager.RINGER_MODE_SILENT -> "silent"
                        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                        else -> "normal"
                    }
                )
                put("music_playing", audio.isMusicActive)
            }

            val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notifications != null) {
                put("do_not_disturb", notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL)
            }

            put("minutes_since_boot", SystemClock.elapsedRealtime() / 60_000)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
