package me.rerere.rikkahub.data.ai.transformers

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.toJavaInstant

/**
 * 时间提醒注入转换器
 *
 * 在时间间隔较大的消息之前自动注入 <time_reminder>，帮助 AI 了解对话的时间间隔
 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableTimeReminder) return messages
        return applyTimeReminder(messages, ctx.assistant.timeReminderIntervalMinutes)
    }
}

internal fun applyTimeReminder(
    messages: List<UIMessage>,
    intervalMinutes: Int = 60,
): List<UIMessage> {
    val thresholdSeconds = intervalMinutes.coerceAtLeast(1).toLong() * 60
    val result = mutableListOf<UIMessage>()
    val tz = TimeZone.currentSystemDefault()

    var firstUserFound = false
    for (i in messages.indices) {
        val current = messages[i]
        if (current.role == MessageRole.USER) {
            val currInstant = current.createdAt.toInstant(tz)
            if (!firstUserFound) {
                firstUserFound = true
                result.add(buildTimeReminderMessage(null, currInstant))
            } else {
                val previous = messages[i - 1]
                val prevInstant = previous.createdAt.toInstant(tz)
                val gapSeconds = (currInstant - prevInstant).inWholeSeconds

                if (gapSeconds > thresholdSeconds) {
                    result.add(buildTimeReminderMessage(gapSeconds, currInstant))
                }
            }
        }
        result.add(current)
    }

    return result
}

private fun buildTimeReminderMessage(gapSeconds: Long?, instant: Instant): UIMessage {
    val javaInstant = instant.toJavaInstant()
    val dayOfWeek = javaInstant.atZone(ZoneId.systemDefault()).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
    val timeStr = javaInstant.toLocalDateTime()
    val content = if (gapSeconds != null) {
        val gapText = formatGap(gapSeconds)
        "<time_reminder>Current time: $dayOfWeek, $timeStr ($gapText since last message)</time_reminder>"
    } else {
        "<time_reminder>Current time: $dayOfWeek, $timeStr</time_reminder>"
    }
    return UIMessage.user(content).copy(isSynthetic = true)
}

private fun formatGap(seconds: Long): String {
    val days = seconds / 86400
    val hours = seconds % 86400 / 3600
    val minutes = seconds % 3600 / 60
    return when {
        days > 0 -> if (hours > 0) "$days d $hours h" else "$days d"
        hours > 0 -> if (minutes > 0) "$hours h $minutes min" else "$hours h"
        else -> "$minutes min"
    }
}
