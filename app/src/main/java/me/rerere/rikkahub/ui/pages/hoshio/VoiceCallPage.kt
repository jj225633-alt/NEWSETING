package me.rerere.rikkahub.ui.pages.hoshio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.hoshio.HoshioChat
import me.rerere.rikkahub.hoshio.voice.IncomingCall
import me.rerere.rikkahub.hoshio.voice.VoiceAudio
import me.rerere.rikkahub.hoshio.voice.VoiceCallState
import me.rerere.rikkahub.hoshio.voice.VoicePlayer
import me.rerere.rikkahub.hoshio.voice.listenOnce
import me.rerere.rikkahub.hoshio.voice.splitForSpeech
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import java.io.File

private enum class CallPhase(val label: String) {
    CONNECTING("接通中…"), THINKING("他在想…"), SPEAKING("他在說話"), LISTENING("換你說"), MUTED("已靜音"), ERROR("通話出了點問題")
}

/**
 * 星汐「语音通话」：用 Android 内建的语音辨识听她说话，送给他，再用她选的声音（例如 ElevenLabs）念出他的回覆。
 * [reason] 不为空时是他打来的电话（她接起来了）。通话内容会照常留在聊天纪录里。
 */
@Composable
fun VoiceCallPage(reason: String? = null) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val assistant = settings.assistants.firstOrNull { it.id == settings.assistantId }
    val name = assistant?.name?.ifBlank { null } ?: "時澈"

    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var phase by remember { mutableStateOf(CallPhase.CONNECTING) }
    var partial by remember { mutableStateOf("") }
    var lastSaid by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var muted by remember { mutableStateOf(false) }
    var speakJob by remember { mutableStateOf<Job?>(null) }
    var listenJob by remember { mutableStateOf<Job?>(null) }
    val startedAt = remember { System.currentTimeMillis() }
    // 星汐: 被打斷時她真正聽到的部分（只算整段播完的句子），下一輪告訴他
    var interruptedNote by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableStateOf(0L) }

    // 通话期间：萤幕不关、告诉他在讲电话；挂断时清掉
    DisposableEffect(Unit) {
        IncomingCall.cancel(context)
        VoiceCallState.active.value = true
        (context as? Activity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            VoiceCallState.active.value = false
            VoicePlayer.stop()
            (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.RECORD_AUDIO)
        while (true) {
            elapsed = (System.currentTimeMillis() - startedAt) / 1000
            delay(1000)
        }
    }

    // 一段一段合成、一段一段念：念这段的时候先合成下一段
    suspend fun speak(text: String) {
        val clean = VoiceAudio.speakable(text)
        if (clean.isBlank()) return
        lastSaid = clean
        phase = CallPhase.SPEAKING
        val chunks = splitForSpeech(clean)
        var played = 0
        coroutineScope {
            val job = launch {
                val files = Channel<File>(capacity = 2)
                launch {
                    chunks.forEachIndexed { i, chunk ->
                        files.send(VoiceAudio.synthesize(context, chunk, "call_${startedAt}_${System.nanoTime()}_$i"))
                    }
                    files.close()
                }
                for (f in files) {
                    try {
                        VoicePlayer.play(f)
                        played++
                    } finally {
                        f.delete()
                    }
                }
            }
            speakJob = job
            job.join()
            speakJob = null
        }
        // 沒念完就被打斷：記下她實際聽到哪裡
        if (played < chunks.size) {
            val heard = chunks.take(played).joinToString("")
            val missed = chunks.drop(played).joinToString("")
            interruptedNote = "(She interrupted you mid-sentence. She only heard: \"${heard.ifBlank { "(nothing)" }}\". " +
                "She did NOT hear: \"${missed.take(300)}\". Treat the unheard part as never said.)"
            lastSaid = heard.ifBlank { "（被打斷了）" }
        }
    }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        val conversation = HoshioChat.latestConversation()?.first
        if (conversation == null) {
            phase = CallPhase.ERROR
            errorText = "還沒有和他聊過天，先在聊天室跟他說一句話再打給他吧"
            return@LaunchedEffect
        }
        runCatching {
            phase = CallPhase.THINKING
            val opening = HoshioChat.askConversation(
                conversation.id,
                "語音通話",
                if (reason != null) {
                    "She picked up the voice call you made (you called because: $reason). Speak first: 1-2 short, warm spoken sentences."
                } else {
                    "She is calling you on a voice call right now. Pick up naturally, like answering the phone: 1-2 short spoken sentences."
                },
            )
            speak(opening)
        }.onFailure {
            phase = CallPhase.ERROR
            errorText = it.message ?: "語音合成失敗，請確認已設定語音服務"
            return@LaunchedEffect
        }
        // 星汐: 沉默事件——她一直沒說話時，讓他自己決定要開口還是繼續等
        var silentSince = System.currentTimeMillis()
        var nextCheckSec = 15L
        var spokeOnce = true
        while (isActive) {
            if (muted) {
                phase = CallPhase.MUTED
                silentSince = System.currentTimeMillis()
                delay(300)
                continue
            }
            phase = CallPhase.LISTENING
            partial = ""
            var heard: String? = null
            coroutineScope {
                val job = launch { heard = listenOnce(context) { partial = it } }
                listenJob = job
                job.join()
                listenJob = null
            }
            val said = heard
            if (said == null) {
                val silentSec = (System.currentTimeMillis() - silentSince) / 1000
                if (silentSec >= nextCheckSec && !muted) {
                    phase = CallPhase.THINKING
                    val event = (if (spokeOnce) "On the voice call, after your last words finished playing, " else "On the voice call, since it connected, ") +
                        "she has not said anything for $silentSec seconds. Her microphone is on and the call is connected."
                    val fresh = HoshioChat.fresh(conversation)
                    val decision = me.rerere.rikkahub.hoshio.care.WakeDecision.decide(
                        context, fresh, "通話中她沒說話",
                        "$event Based on the conversation, decide whether to keep waiting quietly (like staying on the line together) or to speak up naturally.",
                    )
                    if (decision != null && !decision.talk) {
                        // 他選擇繼續等：下次隔久一點再問
                        nextCheckSec = (silentSec + (nextCheckSec * 2).coerceAtMost(300)).coerceAtMost(silentSec + 300)
                        continue
                    }
                    val reply = runCatching {
                        HoshioChat.askConversation(
                            conversation.id, "通話中她沒說話",
                            "$event You decided to speak. Say 1-2 short, natural spoken sentences (no actions, no emoji)." +
                                (decision?.thought?.takeIf { it.isNotBlank() }?.let { " Your thought was: \"$it\"." } ?: ""),
                        )
                    }.getOrDefault("")
                    runCatching { speak(reply) }.onFailure { errorText = it.message }
                    silentSince = System.currentTimeMillis()
                    nextCheckSec = 15
                    spokeOnce = true
                }
                continue
            }
            partial = said
            phase = CallPhase.THINKING
            interruptedNote?.let { me.rerere.rikkahub.hoshio.voice.VoiceCallState.interruptedNote.value = it }
            interruptedNote = null
            val reply = runCatching { HoshioChat.sendAndWait(conversation.id, said) }.getOrDefault("")
            runCatching { speak(reply) }.onFailure { errorText = it.message }
            silentSince = System.currentTimeMillis()
            nextCheckSec = 15
        }
    }

    // 画面
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (phase == CallPhase.SPEAKING || phase == CallPhase.LISTENING) 1.08f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(Modifier.fillMaxSize().background(Color(0xFF0E1222))) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Box(Modifier.size(132.dp).scale(pulse).background(Color(0x33D8C49A), CircleShape), contentAlignment = Alignment.Center) {
                if (assistant != null) UIAvatar(name = name, value = assistant.avatar, modifier = Modifier.size(112.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(name, color = Color(0xFFF2EBDD), fontSize = 28.sp, fontFamily = FontFamily.Serif)
            Text(
                "%02d:%02d・%s".format(elapsed / 60, elapsed % 60, phase.label),
                color = Color(0xFFBAA484),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                when {
                    errorText != null -> errorText!!
                    phase == CallPhase.LISTENING || phase == CallPhase.THINKING -> partial.ifBlank { "……" }
                    else -> lastSaid
                },
                color = if (errorText != null) Color(0xFFE8A0A0) else Color(0xFFE6E2D8),
                fontSize = 17.sp,
                lineHeight = 26.sp,
                textAlign = TextAlign.Center,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!granted) {
                Text("需要麥克風權限才能通話", color = Color(0xFFE8A0A0), modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilledTonalButton(onClick = {
                    muted = !muted
                    if (muted) listenJob?.cancel()
                }) { Text(if (muted) "🎙️ 取消靜音" else "🔇 靜音") }
                FilledTonalButton(enabled = phase == CallPhase.SPEAKING, onClick = {
                    speakJob?.cancel()
                    VoicePlayer.stop()
                }) { Text("✋ 打斷他") }
            }
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.size(76.dp).background(Color(0xFFD9534F), CircleShape)
                    .then(Modifier),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.TextButton(onClick = { navController.popBackStack() }) {
                    Text("📞", fontSize = 28.sp)
                }
            }
            Text("掛斷", color = Color(0xFFBAA484), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}
