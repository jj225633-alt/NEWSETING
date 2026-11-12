package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 星汐: 像 Orbis 一樣，在她的每一則訊息後面附上「她是什麼時候傳的」（只在送給模型時加，不改存檔）。
 * 每則的時間是固定的，所以不影響提示詞快取；最後一則的時間就是「現在」。
 * 不然他只有隔超過一小時才會收到時間提醒，聊著聊著就會把時間猜錯。
 */
object MessageTimeTransformer : InputMessageTransformer {
    private val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd（E）HH:mm", Locale.TRADITIONAL_CHINESE)

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val tz = TimeZone.currentSystemDefault()
        val lastHuman = messages.indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }
        return messages.mapIndexed { i, m ->
            if (m.role != MessageRole.USER || m.isSynthetic || m.parts.none { it is UIMessagePart.Text || it is UIMessagePart.Image || it is UIMessagePart.Audio }) return@mapIndexed m
            val at = runCatching {
                Instant.ofEpochMilli(m.createdAt.toInstant(tz).toEpochMilliseconds()).atZone(ZoneId.systemDefault()).format(fmt)
            }.getOrNull() ?: return@mapIndexed m
            val note = if (i == lastHuman) "〔她傳這則的時間：$at，也就是現在。要說時間時以這個為準，不要自己推算。〕"
            else "〔她傳這則的時間：$at（這是過去的時間，不是現在）〕"
            m.copy(parts = m.parts + UIMessagePart.Text(note))
        }
    }
}
