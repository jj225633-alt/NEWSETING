package me.rerere.rikkahub.hoshio.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * 星汐「她的語音訊息」：按住錄音，直接把聲音檔給他（Gemini 聽得懂語氣、嘆氣、笑聲）。
 * 存在 filesDir/upload，和其他附件一樣會被備份；檔名以 hervoice_ 開頭，聊天室會顯示成語音泡泡。
 */
class HerVoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null

    fun start(): Boolean = runCatching {
        val dir = File(context.filesDir, "upload").apply { mkdirs() }
        val f = File(dir, "hervoice_${System.currentTimeMillis()}.aac")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16000)
        r.setAudioEncodingBitRate(32000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = f
        startedAt = System.currentTimeMillis()
        true
    }.getOrElse {
        release()
        false
    }

    /** 停止；太短（不到 0.8 秒）或失敗回傳 null 並刪掉檔案 */
    fun stop(): Uri? {
        val r = recorder ?: return null
        val f = file
        val ms = System.currentTimeMillis() - startedAt
        val ok = runCatching { r.stop() }.isSuccess
        release()
        if (f == null) return null
        if (!ok || ms < 800 || f.length() < 1000) {
            f.delete()
            return null
        }
        return Uri.fromFile(f)
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        release()
        file?.delete()
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
    }

    companion object {
        fun isHerVoice(url: String) = url.substringAfterLast('/').startsWith("hervoice_")

        fun durationSec(path: String): Int = runCatching {
            val m = MediaMetadataRetriever()
            try {
                m.setDataSource(path)
                ((m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
            } finally {
                m.release()
            }
        }.getOrDefault(0)
    }
}
