package me.rerere.rikkahub.ui.pages.hoshio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.ui.pages.hoshio.splash.LocalHoshioChatGlass
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * 等待回覆的心跳（移植自 orbis-tw）：兩顆心中間一條心電圖，光點每 2.5 秒掃過一次。
 * 星汐主題白天是海水藍，晚上是星星的金屬金（帶一道流動的光澤）；下面顯示已經等了幾秒，方便判斷是不是卡住。
 */
@Composable
fun HoshioHeartbeat(modifier: Modifier = Modifier, status: String? = null, startedAt: Long? = null) {
    val dark = LocalDarkMode.current
    val hoshio = LocalHoshioChatGlass.current
    val base: Color
    val shine: Color
    when {
        hoshio && dark -> { base = Color(0xFFC9AE78); shine = Color(0xFFFFF1CF) }
        hoshio -> { base = Color(0xFF2E8FB0); shine = Color(0xFF8FE3F2) }
        else -> { base = MaterialTheme.colorScheme.primary; shine = MaterialTheme.colorScheme.primary }
    }
    val transition = rememberInfiniteTransition(label = "heartbeat")
    val sweep by transition.animateFloat(
        initialValue = -.25f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    // 心臟跟著心電圖的尖峰跳一下
    val beat = (1f - kotlin.math.abs(sweep - .3f) * 6f).coerceIn(0f, 1f)
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        val start = startedAt ?: System.currentTimeMillis()
        while (true) {
            seconds = (System.currentTimeMillis() - start) / 1000
            delay(1000)
        }
    }
    Column(
        modifier = modifier.widthIn(max = 220.dp).fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "等待回覆" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            // 金屬感：沿對角線的漸層，光澤跟著掃描位置移動
            val metal = Brush.linearGradient(
                listOf(base, shine, base),
                start = Offset(size.width * (sweep - .3f), 0f),
                end = Offset(size.width * (sweep + .3f), size.height),
            )
            val heartSize = minOf(16.dp.toPx(), size.width * .1f) * (1f + .12f * beat)
            fun heart(left: Float) = Path().apply {
                val top = (size.height - heartSize) / 2f
                fun x(v: Float) = left + v * heartSize
                fun y(v: Float) = top + v * heartSize
                moveTo(x(.5f), y(.9f))
                cubicTo(x(.35f), y(.78f), x(.03f), y(.55f), x(.03f), y(.32f))
                cubicTo(x(.03f), y(.05f), x(.36f), y(.02f), x(.5f), y(.24f))
                cubicTo(x(.64f), y(.02f), x(.97f), y(.05f), x(.97f), y(.32f))
                cubicTo(x(.97f), y(.55f), x(.65f), y(.78f), x(.5f), y(.9f))
                close()
            }
            drawPath(heart(0f), metal)
            drawPath(heart(size.width - heartSize), metal)
            val left = heartSize + minOf(8.dp.toPx(), size.width * .05f)
            val lineWidth = (size.width - left * 2f).coerceAtLeast(0f)
            val points = listOf(0f to 24f, 34f to 24f, 42f to 6f, 50f to 42f, 58f to 14f, 66f to 24f, 150f to 24f)
            val line = Path().apply {
                points.forEachIndexed { index, (x, y) ->
                    val px = left + x / 150f * lineWidth
                    val py = y / 48f * size.height
                    if (index == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(line, base.copy(alpha = .30f), style = stroke)
            if (lineWidth > 0f) {
                val center = left + sweep * lineWidth
                val half = lineWidth * .16f
                drawPath(
                    line,
                    Brush.linearGradient(
                        listOf(base.copy(alpha = 0f), base, shine, base, base.copy(alpha = 0f)),
                        start = Offset(center - half, 0f), end = Offset(center + half, 0f),
                    ),
                    style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawCircle(shine.copy(alpha = .9f), 2.dp.toPx(), Offset(center.coerceIn(left, left + lineWidth), size.height / 2f).let { o ->
                    // 光點貼著心電圖走：取最近的折線高度
                    val rel = (o.x - left) / lineWidth * 150f
                    val seg = points.zipWithNext().firstOrNull { (a, b) -> rel >= a.first && rel <= b.first }
                    val y = seg?.let { (a, b) -> a.second + (b.second - a.second) * ((rel - a.first) / (b.first - a.first).coerceAtLeast(.001f)) } ?: 24f
                    Offset(o.x, y / 48f * size.height)
                })
            }
        }
        Text(
            buildString {
                append(status?.takeIf { it.isNotBlank() } ?: "等待回覆")
                if (seconds >= 3) append(" · ").append(if (seconds < 60) "${seconds} 秒" else "${seconds / 60} 分 ${seconds % 60} 秒")
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (hoshio) base else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 星汐: 記住每個對話開始等回覆的時間，離開聊天頁再回來時秒數接著算，不會歸零 */
object HoshioWaitClock {
    private val starts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun start(id: String): Long = starts.getOrPut(id) { System.currentTimeMillis() }
    fun clear(id: String) { starts.remove(id) }
}
