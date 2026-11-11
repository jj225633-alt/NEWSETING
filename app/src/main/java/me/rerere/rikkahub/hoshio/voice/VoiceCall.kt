package me.rerere.rikkahub.hoshio.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import kotlin.coroutines.resume

/** 星汐「语音」的开关 */
class VoicePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hoshio_voice", Context.MODE_PRIVATE)

    /** 允许他主动打电话给她 */
    var allowIncoming: Boolean
        get() = prefs.getBoolean("allow_incoming", true)
        set(value) = prefs.edit { putBoolean("allow_incoming", value) }

    /** 拒接后多久内他不能再打（分钟） */
    var rejectCooldownMinutes: Int
        get() = prefs.getInt("reject_cooldown", 15)
        set(value) = prefs.edit { putInt("reject_cooldown", value) }

    var lastRejectAt: Long
        get() = prefs.getLong("last_reject", 0L)
        set(value) = prefs.edit { putLong("last_reject", value) }

    /** 他主动传讯息时用他的声音念出来 */
    var readAloud: Boolean
        get() = prefs.getBoolean("read_aloud", false)
        set(value) = prefs.edit { putBoolean("read_aloud", value) }

    /** 锁屏时也念 */
    var readWhenLocked: Boolean
        get() = prefs.getBoolean("read_locked", false)
        set(value) = prefs.edit { putBoolean("read_locked", value) }
}

/** 通话中：送给他的讯息会附上「现在在讲电话」的提示 */
object VoiceCallState {
    val active = MutableStateFlow(false)
    /** 她打斷他時實際聽到哪裡；下一次送給他時附上，用過就清掉 */
    val interruptedNote = MutableStateFlow<String?>(null)
}

/**
 * 用 Android 内建的语音辨识（Google）听一句话。
 * 回传辨识出的文字；没听到或出错回传 null。[onPartial] 会收到辨识中的文字。
 */
suspend fun listenOnce(context: Context, onPartial: (String) -> Unit = {}): String? = withContext(Dispatchers.Main) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
    suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        fun finish(value: String?) {
            runCatching { recognizer.destroy() }
            if (cont.isActive) cont.resume(value)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) = finish(null)
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.ifEmpty { null })
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        recognizer.startListening(intent)
        cont.invokeOnCancellation {
            CoroutineScope(Dispatchers.Main).launch { runCatching { recognizer.cancel(); recognizer.destroy() } }
        }
    }
}

/** 把一段回覆切成适合一段一段念的小块（约一两句） */
fun splitForSpeech(text: String): List<String> {
    val sentences = Regex("[^。！？!?…\\n]+[。！？!?…]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for (s in sentences) {
        current.append(s)
        if (current.length >= 24) {
            chunks += current.toString()
            current.clear()
        }
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks
}

/**
 * 他打电话给她：响铃通知，接听会开通话画面，拒接会记下时间（冷却期间他不能再打）。
 */
object IncomingCall {
    private const val CHANNEL = "hoshio_incoming_call"
    const val NOTIFICATION_ID = 7310
    const val ACTION_VOICE_CALL = "app.hoshio.action.VOICE_CALL"
    const val EXTRA_REASON = "callReason"
    private const val ACTION_DECLINE = "app.hoshio.action.DECLINE_CALL"

    fun ring(context: Context, callerName: String, reason: String) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "他打來的電話", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 600, 800, 600, 800)
                },
            )
        }
        val answer = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            action = ACTION_VOICE_CALL
            putExtra(EXTRA_REASON, reason)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val answerIntent = answer?.let { PendingIntent.getActivity(app, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val declineIntent = PendingIntent.getBroadcast(
            app, NOTIFICATION_ID + 1,
            Intent(app, DeclineCallReceiver::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle("📞 $callerName 打電話給你")
            .setContentText(reason.ifBlank { "想聽聽你的聲音" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(45_000)
            .apply { answerIntent?.let { setContentIntent(it); addAction(0, "接聽", it) } }
            .addAction(0, "拒接", declineIntent)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}

class DeclineCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IncomingCall.cancel(context)
        VoicePrefs(context).lastRejectAt = System.currentTimeMillis()
        // 告诉他被拒接了（他可以选择要不要回一句）
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                me.rerere.rikkahub.hoshio.HoshioChat.post(
                    "她拒接了",
                    "Automatic event: she declined your voice call just now (maybe busy or not able to talk). " +
                        "Respect it. You may send one short, understanding message, or stay silent with stay_silent.",
                )
            }
        }
    }
}
