package me.rerere.rikkahub.ui.pages.hoshio.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import me.rerere.rikkahub.ui.theme.presets.HoshioPalette
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.random.Random

/**
 * 星汐聊天背景：白天是緩慢流動的淺海焦散，夜晚是低彩度星空。
 * 刻意加上一層薄紗、保持低解析度（放大後自然柔焦），讓文字比背景更顯眼。
 * 動畫約每秒 20 張，省電模式、系統動畫關閉或畫面不在前景時完全靜止。
 */
@Composable
internal fun HoshioChatBackdrop(dark: Boolean, animate: Boolean, modifier: Modifier = Modifier) {
    val time = remember { mutableFloatStateOf(7f) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it } - (time.floatValue * 1_000_000_000f).toLong()
            while (true) {
                withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
                delay(33)
            }
        }
    }
    val caustics = remember(dark) { if (dark) null else CausticField(w = 48, h = 104) }
    val stars = remember(dark) { if (dark) backdropStars() else emptyList() }
    Canvas(modifier.clearAndSetSemantics { }) {
        val t = time.floatValue
        if (dark) drawNightBackdrop(stars, t) else drawDayBackdrop(caustics!!, t)
    }
}

private fun DrawScope.drawDayBackdrop(field: CausticField, t: Float) {
    drawRect(Brush.verticalGradient(listOf(
        Color(0xFF9EDFE1), Color(0xFFBFE8E4), Color(0xFFDCEEE7), HoshioPalette.Sand)))
    // 斜射陽光
    drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .32f), Color.Transparent),
        start = Offset(size.width, 0f), end = Offset(size.width * .2f, size.height * .45f)))
    // 會搖晃的光柱（水面折射下來的陽光）
    val w = size.width
    val h = size.height
    repeat(4) { k ->
        val sway = sin(t * (.35f + k * .07f) + k * 1.7f)
        val x = w * (.12f + k * .26f) + sway * w * .07f
        val a = (.10f + .06f * sin(t * .5f + k * 2.3f)).coerceAtLeast(0f)
        rotate(18f, pivot = Offset(x, 0f)) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = a), Color.Transparent),
                    startX = x - w * .06f, endX = x + w * .06f),
                topLeft = Offset(x - w * .06f, -h * .1f), size = androidx.compose.ui.geometry.Size(w * .12f, h * 1.2f),
            )
        }
    }
    // 焦散：流動快一點、清楚一點
    field.update(t * .75f)
    drawImage(field.image, srcOffset = IntOffset.Zero, srcSize = IntSize(field.w, field.h),
        dstOffset = IntOffset.Zero, dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()),
        alpha = .72f, filterQuality = FilterQuality.Low)
    // 薄紗：讓背景退後（比之前薄，動態看得出來）
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .20f), Color.White.copy(alpha = .32f))))
    // 水面閃光：多一點、會慢慢飄
    repeat(12) { i ->
        val drift = (t * (.006f + (i % 4) * .002f)) % 1f
        val x = size.width * (((((i * 37 + 11) % 97) / 97f) + drift) % 1f)
        val y = size.height * (.04f + ((i * 23 + 5) % 41) / 41f * .45f) + sin(t * .6f + i) * 6f * density
        val tw = (sin(t * (1.6f + (i % 3) * .5f) + i * 2.1f) * .5f + .5f).let { it * it }
        drawSparkle(Offset(x, y), (3f + i % 4) * density, Color.White.copy(alpha = .8f * tw))
    }
}

private class BackdropStar(val x: Float, val y: Float, val r: Float, val a: Float, val twinkle: Boolean)

private fun backdropStars(): List<BackdropStar> {
    val rnd = Random(19)
    val list = ArrayList<BackdropStar>(240)
    repeat(130) {
        list += BackdropStar(rnd.nextFloat(), rnd.nextFloat(), .5f + rnd.nextFloat() * .8f,
            .3f + rnd.nextFloat() * .45f, rnd.nextInt(2) == 0)
    }
    // 由左下往右上的銀河帶
    repeat(130) {
        val u = rnd.nextFloat()
        val spread = (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 1.5f) * .16f
        list += BackdropStar((u + spread * .7f).coerceIn(0f, 1f), (1f - u * .95f + spread * .5f).coerceIn(0f, 1f),
            .4f + rnd.nextFloat() * .5f, .2f + rnd.nextFloat() * .35f, rnd.nextInt(3) == 0)
    }
    return list
}

private fun DrawScope.drawNightBackdrop(stars: List<BackdropStar>, t: Float) {
    drawRect(Brush.verticalGradient(listOf(HoshioPalette.NightTop, HoshioPalette.NightUpper,
        HoshioPalette.NightLower, HoshioPalette.NightBottom)))
    val w = size.width
    val h = size.height
    // 星雲會慢慢飄、呼吸
    val c1 = Offset(w * (.25f + .06f * sin(t * .05f)), h * (.35f + .04f * sin(t * .04f + 1f)))
    val c2 = Offset(w * (.8f + .05f * sin(t * .045f + 2f)), h * (.7f + .05f * sin(t * .05f + 3f)))
    val breathe = .8f + .2f * sin(t * .25f)
    drawCircle(Brush.radialGradient(listOf(HoshioPalette.NightNebulaCool.copy(alpha = .16f * breathe), Color.Transparent),
        center = c1, radius = w * .9f), w * .9f, c1)
    drawCircle(Brush.radialGradient(listOf(HoshioPalette.NightNebulaWarm.copy(alpha = .14f * (1.6f - breathe)), Color.Transparent),
        center = c2, radius = w * .8f), w * .8f, c2)
    // 整片星空緩緩往右流動（約 3 分鐘流過一個螢幕寬）
    val flow = t * .0055f
    stars.forEachIndexed { i, s ->
        val tw = if (s.twinkle) (.5f + .5f * sin(t * (1.4f + (i % 5) * .45f) + i * 1.3f)).let { .15f + .85f * it * it } else 1f
        val x = ((s.x + flow * (.6f + s.r * .5f)) % 1f) * w
        val pos = Offset(x, s.y * h)
        if (s.twinkle && tw > .85f) drawCircle(HoshioPalette.StarGlow.copy(alpha = .18f * tw), s.r * 3f * density, pos)
        drawCircle(HoshioPalette.StarWarm.copy(alpha = (s.a * 1.3f).coerceAtMost(1f) * tw), s.r * density, pos)
    }
    // 流星：大約每 7 秒劃過一顆
    val period = 7f
    val n = (t / period).toInt()
    val ph = (t % period) / 1.1f
    if (ph < 1f) {
        val rnd = Random(n * 31 + 7)
        val sx = w * (.15f + rnd.nextFloat() * .7f)
        val sy = h * (.05f + rnd.nextFloat() * .4f)
        val len = w * (.25f + rnd.nextFloat() * .15f)
        val dir = Offset(-.8f, .6f)
        val head = Offset(sx + dir.x * len * ph, sy + dir.y * len * ph)
        val tail = Offset(head.x - dir.x * len * .35f, head.y - dir.y * len * .35f)
        val fade = sin(ph * Math.PI.toFloat())
        drawLine(Brush.linearGradient(listOf(Color.Transparent, HoshioPalette.StarWarm.copy(alpha = .9f * fade)), start = tail, end = head),
            tail, head, strokeWidth = 1.6f * density)
        drawCircle(HoshioPalette.StarWarm.copy(alpha = fade), 1.8f * density, head)
    }
    // 暖色四芒星
    listOf(Offset(.18f, .14f) to 6f, Offset(.82f, .22f) to 4.5f, Offset(.66f, .58f) to 3.5f,
        Offset(.28f, .72f) to 4f, Offset(.9f, .86f) to 3f).forEachIndexed { i, (p, r) ->
        val tw = .4f + .6f * sin(t * 1.8f + i * 1.9f).let { it * it }
        val pos = Offset(((p.x + flow * .8f) % 1f) * w, p.y * h)
        drawCircle(HoshioPalette.StarGlow.copy(alpha = .10f * tw), r * 1.6f * density, pos)
        drawSparkle(pos, r * density, HoshioPalette.StarWarm.copy(alpha = .8f * tw))
    }
    // 薄紗：降低對比，讓泡泡和文字更清楚
    drawRect(Color(0xFF11131B).copy(alpha = .14f))
}
