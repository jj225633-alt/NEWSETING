package me.rerere.rikkahub.hoshio.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.uuid.Uuid

/**
 * 星汐「他的声音」：用她选好的语音服务（例如 ElevenLabs）把一段话合成成音档，
 * 以及在 App 里播放。语音讯息、通话、念通知都用这里。
 */
object VoiceAudio {
    fun dir(context: Context) = File(context.filesDir, "hoshio_voice").apply { mkdirs() }

    /** 念之前清掉 <think>、Markdown 符号和网址，留下要说的话 */
    fun speakable(text: String): String = text
        .replace(Regex("<think>[\\s\\S]*?</think>"), "")
        .replace(Regex("```[\\s\\S]*?```"), "")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*_#>`~|]"), "")
        .replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")
        .replace(Regex("\\n{2,}"), "\n")
        .trim()

    /** 合成一段话，存成档案回传；没有设定语音服务或失败时丢例外 */
    suspend fun synthesize(context: Context, text: String, name: String = Uuid.random().toString().take(12)): File = withContext(Dispatchers.IO) {
        val settings = getKoin().get<SettingsStore>().settingsFlow.value
        val provider = settings.getSelectedTTSProvider() ?: error("還沒有設定語音服務（設定 → 語音服務 → 語音合成）")
        val chunks = getKoin().get<TTSManager>().generateSpeech(provider, TTSRequest(text)).toList()
        require(chunks.isNotEmpty()) { "語音服務沒有回傳聲音" }
        val format = chunks.first().format
        val sampleRate = chunks.firstNotNullOfOrNull { it.sampleRate } ?: 24000
        val (bytes, ext) = when (format) {
            AudioFormat.PCM -> wav(chunks.fold(ByteArrayOutputStream()) { out, c -> out.apply { write(c.data) } }.toByteArray(), sampleRate) to "wav"
            AudioFormat.WAV -> chunks.first().data.let { first ->
                // 多段 WAV 时只接资料区（跳过后面每段的 44 位元组档头）
                if (chunks.size == 1) first else ByteArrayOutputStream().apply {
                    write(first)
                    chunks.drop(1).forEach { write(it.data, 44.coerceAtMost(it.data.size), (it.data.size - 44).coerceAtLeast(0)) }
                }.toByteArray()
            } to "wav"
            AudioFormat.OGG, AudioFormat.OPUS -> chunks.concat() to "ogg"
            AudioFormat.AAC -> chunks.concat() to "aac"
            AudioFormat.MP3 -> chunks.concat() to "mp3"
        }
        File(dir(context), "$name.$ext").apply { writeBytes(bytes) }
    }

    private fun List<me.rerere.tts.model.AudioChunk>.concat(): ByteArray =
        fold(ByteArrayOutputStream()) { out, c -> out.apply { write(c.data) } }.toByteArray()

    /** 16-bit 单声道 PCM 加上 WAV 档头 */
    private fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }

    fun durationSeconds(file: File): Int = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt().coerceAtLeast(1)
        } finally {
            r.release()
        }
    }.getOrDefault(0)
}

/** 一次只播一段；新的开始会停掉旧的 */
object VoicePlayer {
    private var player: MediaPlayer? = null

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    /** 播放到结束（或被取消、被 [stop]）才回传 */
    suspend fun play(file: File, volume: Float = 1f): Unit = suspendCancellableCoroutine { cont ->
        stop()
        val p = MediaPlayer()
        player = p
        val finish = {
            if (player === p) stop()
            if (cont.isActive) cont.resume(Unit)
        }
        runCatching {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { finish() }
            p.setOnErrorListener { _, _, _ -> finish(); true }
            p.prepare()
            v@ run { val v = volume.coerceIn(0.05f, 1f); p.setVolume(v, v) }
            p.start()
        }.onFailure { finish() }
        cont.invokeOnCancellation { if (player === p) stop() }
    }
}
