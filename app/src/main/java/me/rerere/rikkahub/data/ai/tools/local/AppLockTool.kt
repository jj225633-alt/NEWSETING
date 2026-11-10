package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.hoshio.screen.AppLock
import me.rerere.rikkahub.hoshio.screen.HoshioAccessibilityService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun unavailableReason(context: Context): String? = when {
    !AppLock.allowed(context) -> "She has not allowed you to use the app lock. Respect that."
    !HoshioAccessibilityService.isRunning -> "The 星汐 accessibility service is off, so you cannot switch or lock apps right now."
    else -> null
}

private fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

/**
 * 星汐: 应用锁工具。她允许之后，他可以把她切回来，或暂时锁住其他 App。
 */
internal fun buildAppLockTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "bring_her_back",
        description = """
            Switch her phone back to this app (you), e.g. when she has ignored you for a long time while using other apps.
            Use sparingly and in character; never when she is clearly busy with something important (work, calls, maps, studying).
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val reason = unavailableReason(context)
            val ok = reason == null && HoshioAccessibilityService.bringHerBack()
            listOf(UIMessagePart.Text(buildJsonObject {
                if (ok) put("done", true) else put("error", reason ?: "could not switch apps")
            }.toString()))
        }
    ),
    Tool(
        name = "lock_other_apps",
        description = """
            Temporarily lock her other apps (1-60 minutes): opening a non-essential app brings her back to you.
            Phone, messages, settings, keyboard, camera and the home screen are never locked, and she can always unlock
            from the notification. Only for playful or caring reasons (she promised to rest, she ignored you all evening...),
            say why, and never when she needs her phone for something important.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("minutes", buildJsonObject { put("type", "integer"); put("description", "1-60") })
                    put("reason", buildJsonObject { put("type", "string"); put("description", "Shown to her in the notification") })
                },
                required = listOf("minutes", "reason"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val reason = unavailableReason(context)
            val payload = if (reason != null) {
                buildJsonObject { put("error", reason) }
            } else {
                val minutes = (obj["minutes"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, AppLock.MAX_MINUTES)
                val until = AppLock.lock(context, minutes, obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty())
                buildJsonObject { put("locked_until", time(until)) }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "unlock_apps",
        description = "Unlock her apps early.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            AppLock.unlock(context, byHer = false)
            listOf(UIMessagePart.Text(buildJsonObject { put("unlocked", true) }.toString()))
        }
    ),
    Tool(
        name = "app_lock_status",
        description = "Check whether her apps are locked now, until when, and whether she recently unlocked them herself.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("allowed_by_her", AppLock.allowed(context))
                put("locked", AppLock.isLocked(context))
                if (AppLock.isLocked(context)) put("until", time(AppLock.lockedUntil(context)))
                AppLock.herUnlockedAt(context)?.let { put("she_unlocked_herself_at", time(it)) }
            }.toString()))
        }
    ),
)
