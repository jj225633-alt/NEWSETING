package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.backgroundTextGenerationParams
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.hoshio.voice.HerVoiceRecorder
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * 星汐「舊圖片、舊語音只留收據」（參考 Dayflow 的 decayImages）：
 * 只有她最近 [KEEP_RECENT] 則帶圖片／語音的訊息送真的檔案，更早的改成一行收據，附上快速模型寫的一句說明，
 * 不再每一輪都重送整張圖、整段聲音。說明在第一次看到時用快速模型寫好存起來（只寫一次）。
 */
object MediaDecayTransformer : InputMessageTransformer, KoinComponent {
    private const val KEEP_RECENT = 2
    private const val PREFS = "hoshio_media_captions"
    private val providerManager: ProviderManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableSetOf<String>()
    private val lock = Mutex()
    private val fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm")

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)
    fun setEnabled(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("enabled", on) }

    private fun key(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun caption(context: Context, url: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(url), null)
    private fun saveCaption(context: Context, url: String, text: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(key(url), text) }

    private fun isMedia(p: UIMessagePart) = p is UIMessagePart.Image || p is UIMessagePart.Audio

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (!enabled(ctx.context)) return messages
        val withMedia = messages.withIndex().filter { (_, m) -> m.role == MessageRole.USER && m.parts.any(::isMedia) }.map { it.index }
        if (withMedia.isEmpty()) return messages
        val recent = withMedia.takeLast(KEEP_RECENT).toSet()
        // 最近的：先在背景寫好說明，之後變舊時就用得到
        recent.forEach { i -> messages[i].parts.filter(::isMedia).forEach { describeLater(ctx, it) } }
        val old = withMedia.filter { it !in recent }.toSet()
        if (old.isEmpty()) return messages
        return messages.mapIndexed { i, m ->
            if (i !in old) return@mapIndexed m
            val at = runCatching {
                Instant.ofEpochMilli(m.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()).atZone(ZoneId.systemDefault()).format(fmt)
            }.getOrDefault("")
            m.copy(parts = m.parts.map { p ->
                when (p) {
                    is UIMessagePart.Image -> UIMessagePart.Text(receipt(ctx.context, p.url, "一張圖片", at))
                    is UIMessagePart.Audio -> UIMessagePart.Text(receipt(ctx.context, p.url, if (HerVoiceRecorder.isHerVoice(p.url)) "一則語音訊息" else "一段音訊", at))
                    else -> p
                }
            })
        }
    }

    private fun receipt(context: Context, url: String, kind: String, at: String): String {
        val c = caption(context, url)
        return "〔她在 $at 傳過$kind" + (if (!c.isNullOrBlank()) "：$c" else "（內容沒有留下說明）") + "；原檔已不再附上〕"
    }

    private fun describeLater(ctx: TransformerContext, part: UIMessagePart) {
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Audio -> part.url
            else -> return
        }
        if (url.startsWith("data:") && url.length > 2_000_000) return
        if (caption(ctx.context, url) != null) return
        scope.launch {
            lock.withLock { if (!running.add(url)) return@launch }
            try {
                val settings = ctx.settings
                val model = settings.findModelById(settings.fastModelId) ?: return@launch
                val provider = model.findProvider(settings.providers) ?: return@launch
                val ask = if (part is UIMessagePart.Image) {
                    "用一句繁體中文（30 字內）客觀描述這張圖片的內容，只輸出那一句。"
                } else {
                    "用一句繁體中文（40 字內）寫出這段語音說了什麼，以及說話的語氣或情緒，只輸出那一句。"
                }
                val result = providerManager.getProviderByType(provider).generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(part, UIMessagePart.Text(ask)))),
                    params = backgroundTextGenerationParams(model, Uuid.random()),
                )
                val text = result.message.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "").replace("\n", " ").trim().take(80)
                if (text.isNotBlank()) saveCaption(ctx.context, url, text)
            } catch (_: Throwable) {
            } finally {
                lock.withLock { running.remove(url) }
            }
        }
    }
}
