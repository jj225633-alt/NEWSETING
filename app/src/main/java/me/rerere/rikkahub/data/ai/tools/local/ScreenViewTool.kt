package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.hoshio.screen.HoshioAccessibilityService
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_SCREENSHOT_SIDE = 1280

/**
 * 星汐: 让 AI 看一眼使用者目前的手机螢幕（经由无障碍服务截图）。
 */
internal fun buildScreenViewTool(context: Context): Tool = Tool(
    name = "look_at_screen",
    description = """
        Take a screenshot of what is currently on the user's phone screen and look at it.
        Returns the image plus the name of the app in the foreground.
        Only call this when the user asks you to look at their screen, or when an automatic screen-observation event asks you to.
        If the screenshot shows private information (passwords, bank details, private messages from others), do not repeat those details.
        Some apps block screenshots and come out black; in that case just say you could not see it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val error = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                "Screen observation needs Android 11 or newer."
            !HoshioAccessibilityService.isRunning ->
                "The screen observation accessibility service is not enabled. Ask the user to turn on 星汐 screen observation " +
                    "in the assistant's local tools settings (Accessibility → 星汐)."
            else -> null
        }
        if (error != null) {
            listOf(UIMessagePart.Text(buildJsonObject { put("error", error) }.toString()))
        } else {
            val bitmap = HoshioAccessibilityService.capture()
            if (bitmap == null) {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "Could not take a screenshot right now (the screen may be off or locked). Try again later.")
                }.toString()))
            } else {
                val bytes = withContext(Dispatchers.Default) { bitmap.toCompressedJpeg() }
                bitmap.recycle()
                val filesManager = getKoin().get<FilesManager>()
                val uri = filesManager.createChatFilesByByteArrays(
                    listOf(bytes),
                    displayName = "screen.jpg",
                    mimeType = "image/jpeg",
                ).first()
                val packageName = HoshioAccessibilityService.foregroundPackage
                listOf(
                    UIMessagePart.Image(url = uri.toString()),
                    UIMessagePart.Text(buildJsonObject {
                        put("captured_at", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                        packageName?.let {
                            put("foreground_app_package", it)
                            context.appLabel(it)?.let { label -> put("foreground_app", label) }
                        }
                    }.toString()),
                )
            }
        }
    }
)

private fun Bitmap.toCompressedJpeg(): ByteArray {
    val scale = MAX_SCREENSHOT_SIDE.toFloat() / max(width, height)
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(this, (width * scale).roundToInt(), (height * scale).roundToInt(), true)
    } else {
        this
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    if (scaled !== this) scaled.recycle()
    return out.toByteArray()
}

private fun Context.appLabel(packageName: String): String? = runCatching {
    @Suppress("DEPRECATION")
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
}.getOrNull()
