/*
 * 橘瓣 OrangeChat
 * 基于 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目遵循 GNU AGPL v3 开源许可，见目录 LICENSE 文件
 */

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

private const val TIME_GAP_THRESHOLD_SECONDS = 300L // 5 分钟

/**
 * 时间标签注入转换器
 *
 * 在时间上距离上次消息之前自动注入 <time_reminder>，让 AI 了解对话的时间差
 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // Force time reminder injection regardless of assistant setting
        return applyTimeReminder(messages)
    }
}

internal fun applyTimeReminder(messages: List<UIMessage>): List<UIMessage> {
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

                if (gapSeconds > TIME_GAP_THRESHOLD_SECONDS) {
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
    return UIMessage.user(content)
}

private fun formatGap(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return buildString {
        if (days > 0) append("${days}天")
        if (hours > 0) append("${hours}小时")
        if (minutes > 0) append("${minutes}分钟")
        if (isEmpty()) append("不到1分钟")
    }
}
