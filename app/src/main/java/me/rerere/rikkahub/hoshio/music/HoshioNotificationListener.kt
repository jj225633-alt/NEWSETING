package me.rerere.rikkahub.hoshio.music

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * 星汐「一起听音乐」：Android 规定要读取其他 App 正在播放的音乐（MediaSession），
 * 必须有一个通知存取服务。这里只是为了拿到这个权限，不读取、不保存任何通知内容。
 */
class HoshioNotificationListener : NotificationListenerService() {
    companion object {
        fun component(context: Context) = ComponentName(context, HoshioNotificationListener::class.java)

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = component(context)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
