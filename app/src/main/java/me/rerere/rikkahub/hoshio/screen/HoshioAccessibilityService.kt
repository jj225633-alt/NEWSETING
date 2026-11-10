package me.rerere.rikkahub.hoshio.screen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * 星汐「螢幕观察」用的无障碍服务。
 * 只记录目前前景是哪个 App，并在 AI 要看螢幕时截一张图；不读取画面文字、不做任何操作。
 */
class HoshioAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    private var lastBringBackAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
            foregroundPackage = pkg
            enforceAppLock(pkg)
        }
    }

    /** 星汐: 应用锁生效中，切到不是必要的 App 就把她带回星汐 */
    private fun enforceAppLock(pkg: String) {
        if (!AppLock.isLocked(this)) {
            if (AppLock.lockedUntil(this) > 0) AppLock.unlock(this, byHer = false) // 时间到了，收掉通知
            return
        }
        if (!AppLock.allowed(this) || AppLock.isEssential(this, pkg)) return
        val now = System.currentTimeMillis()
        if (now - lastBringBackAt < 1500) return
        lastBringBackAt = now
        AppLock.bringHerBack(this)
    }

    override fun onInterrupt() = Unit

    private var bubble: android.view.View? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideBubble = Runnable { removeBubble() }

    private fun removeBubble() {
        bubble?.let { view -> runCatching { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(view) } }
        bubble = null
    }

    /** 在螢幕上方浮出一个小泡泡（无障碍覆盖层，不需要额外的悬浮窗权限），点一下就收起来 */
    private fun showBubbleInternal(title: String, text: String, durationMs: Long) {
        removeBubble()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val view = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xE61B1F3B.toInt())
                setStroke(dp(1), 0x99E8C872.toInt())
            }
            addView(android.widget.TextView(context).apply {
                this.text = title
                textSize = 12f
                setTextColor(0xFFE8C872.toInt())
            })
            addView(android.widget.TextView(context).apply {
                this.text = text
                textSize = 15f
                maxLines = 5
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFFF5F3FF.toInt())
            })
            setOnClickListener { removeBubble() }
        }
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP
            y = dp(56)
            horizontalMargin = 0.04f
        }
        runCatching {
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).addView(view, params)
            bubble = view
            mainHandler.removeCallbacks(hideBubble)
            mainHandler.postDelayed(hideBubble, durationMs)
        }
    }

    override fun onDestroy() {
        removeBubble()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureInternal(): Bitmap? = suspendCancellableCoroutine { continuation ->
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, Executor { it.run() }, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    screenshot.hardwareBuffer.close()
                    if (continuation.isActive) continuation.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }.onFailure {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    companion object {
        @Volatile
        private var instance: HoshioAccessibilityService? = null

        /** 最近一次切到前景的 App 套件名称 */
        @Volatile
        var foregroundPackage: String? = null
            private set

        val isRunning: Boolean get() = instance != null

        /** 用无障碍服务的身分打开星汐（在背景也被允许） */
        fun bringHerBack(): Boolean = instance?.let { AppLock.bringHerBack(it) } ?: false

        /** 在螢幕上浮出他说的话；服务没开时回传 false */
        fun showBubble(title: String, text: String, durationMs: Long = 15_000): Boolean {
            val service = instance ?: return false
            service.mainHandler.post { service.showBubbleInternal(title, text, durationMs) }
            return true
        }

        /** 截一张目前的螢幕；服务没开或系统版本太旧时回传 null */
        suspend fun capture(): Bitmap? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val service = instance ?: return null
            return withTimeoutOrNull(10_000) { service.captureInternal() }
        }

        /** 系统设定里是否已经打开了星汐的无障碍服务（服务可能还没连上） */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                it.startsWith(context.packageName + "/") && it.endsWith(HoshioAccessibilityService::class.java.simpleName)
            }
        }
    }
}
