package me.rerere.rikkahub.hoshio.pc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class PcRequestInfo(val id: String, val kind: String, val reason: String, val createdAt: Long)

/** 電腦版上傳的截圖結果；ok=false 時 code 說明原因（refused、excluded_app、capture_failed…）。 */
data class PcScreenshotResult(val ok: Boolean, val code: String? = null, val jpeg: ByteArray? = null,
                              val width: Int = 0, val height: Int = 0)

/**
 * AI 要求看電腦畫面時的中介：手機不能主動連到電腦，所以把請求放進佇列，
 * 由電腦版長輪詢取走，人類在電腦上同意後才截圖並上傳。全部只在記憶體中，App 重啟即清空。
 */
object PcScreenshotBroker {
    private class Pending(val info: PcRequestInfo, val result: CompletableDeferred<PcScreenshotResult>)

    private val pending = MutableStateFlow<Map<String, Pending>>(emptyMap())
    @Volatile var lastPollAt: Long = 0L
        private set

    /** 電腦版最近 60 秒內有來拿過請求，才視為線上。 */
    fun desktopOnline(now: Long = System.currentTimeMillis()) = now - lastPollAt <= 60_000L

    suspend fun request(reason: String, timeoutMs: Long = 90_000L): PcScreenshotResult {
        val info = PcRequestInfo(UUID.randomUUID().toString(), "screenshot", reason.take(200), System.currentTimeMillis())
        val entry = Pending(info, CompletableDeferred())
        pending.value = pending.value + (info.id to entry)
        return try {
            withTimeoutOrNull(timeoutMs) { entry.result.await() } ?: PcScreenshotResult(false, "timeout")
        } finally {
            pending.value = pending.value - info.id
        }
    }

    /** 電腦版長輪詢：有請求立刻回傳，否則最多等 [waitMs]。 */
    suspend fun poll(waitMs: Long = 25_000L): List<PcRequestInfo> {
        lastPollAt = System.currentTimeMillis()
        val ready = withTimeoutOrNull(waitMs) { pending.first { it.isNotEmpty() } } ?: emptyMap()
        lastPollAt = System.currentTimeMillis()
        return ready.values.map { it.info }.sortedBy { it.createdAt }
    }

    /** 電腦版回傳結果；請求已逾時或不存在時回傳 false。 */
    fun complete(id: String, result: PcScreenshotResult): Boolean {
        val entry = pending.value[id] ?: return false
        return entry.result.complete(result)
    }
}
