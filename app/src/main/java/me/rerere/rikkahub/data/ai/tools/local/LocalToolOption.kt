package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("chart_display")
    data object ChartDisplay : LocalToolOption()

    // 星汐: 手机状态（电量、网络、静音等）
    @Serializable
    @SerialName("device_status")
    data object DeviceStatus : LocalToolOption()

    // 星汐: 查询当前位置（需要定位权限）
    @Serializable
    @SerialName("location")
    data object Location : LocalToolOption()

    // 星汐: 看一眼目前的螢幕（需要无障碍服务）
    @Serializable
    @SerialName("screen_view")
    data object ScreenView : LocalToolOption()

    // 星汐: 日记（翻阅与在她要求时写）
    @Serializable
    @SerialName("diary")
    data object Diary : LocalToolOption()

    // 星汐: 自己约下次来找她
    @Serializable
    @SerialName("self_reminder")
    data object SelfReminder : LocalToolOption()

    // 星汐: 游戏室（他写的小东西、小网页）
    @Serializable
    @SerialName("playroom")
    data object Playroom : LocalToolOption()

    // 星汐: 纪念日
    @Serializable
    @SerialName("anniversary")
    data object Anniversary : LocalToolOption()

    // 星汐: 天气
    @Serializable
    @SerialName("weather")
    data object Weather : LocalToolOption()

    // 星汐: 他的隐私室
    @Serializable
    @SerialName("private_room")
    data object PrivateRoom : LocalToolOption()

    // 星汐: 书房（一起读书、写批注）
    @Serializable
    @SerialName("library")
    data object Library : LocalToolOption()

    // 星汐: 一起听音乐
    @Serializable
    @SerialName("music")
    data object Music : LocalToolOption()

    // 星汐: 他的生活（状态、选择不说话、写信）
    @Serializable
    @SerialName("life")
    data object Life : LocalToolOption()

    // 星汐: 应用锁（把她切回来、暂时锁住其他 App）
    @Serializable
    @SerialName("app_lock")
    data object AppLock : LocalToolOption()
}
