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
    // 星汐「一起聽」：換歌時記下她在聽什麼（只記歌名、歌手、App，不讀其他通知）
    private val callbacks = mutableMapOf<android.media.session.MediaSession.Token, android.media.session.MediaController.Callback>()
    private var sessionsListener: android.media.session.MediaSessionManager.OnActiveSessionsChangedListener? = null

    private fun track(controllers: List<android.media.session.MediaController>?) {
        val list = controllers.orEmpty()
        list.forEach { c ->
            if (callbacks.containsKey(c.sessionToken)) return@forEach
            val cb = object : android.media.session.MediaController.Callback() {
                override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = log(c)
                override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                    if (state?.state == android.media.session.PlaybackState.STATE_PLAYING) log(c)
                }
            }
            runCatching { c.registerCallback(cb) }
            callbacks[c.sessionToken] = cb
            log(c)
        }
    }

    private fun log(c: android.media.session.MediaController) {
        if (c.playbackState?.state != android.media.session.PlaybackState.STATE_PLAYING) return
        val label = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        val now = c.toNowPlaying(label) ?: return
        runCatching { ListenHistory.log(applicationContext, now.title, now.artist, label) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val listener = android.media.session.MediaSessionManager.OnActiveSessionsChangedListener { track(it) }
            manager.addOnActiveSessionsChangedListener(listener, component(this))
            sessionsListener = listener
            track(manager.getActiveSessions(component(this)))
        }
    }

    override fun onListenerDisconnected() {
        runCatching {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            sessionsListener?.let { manager.removeOnActiveSessionsChangedListener(it) }
        }
        sessionsListener = null
        callbacks.clear()
        super.onListenerDisconnected()
    }

    companion object {
        fun component(context: Context) = ComponentName(context, HoshioNotificationListener::class.java)

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = component(context)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
