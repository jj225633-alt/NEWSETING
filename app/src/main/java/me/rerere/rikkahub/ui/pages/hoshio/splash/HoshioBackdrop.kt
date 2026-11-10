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
                delay(45)
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
    // 焦散放慢到原本的三分之一，比較安靜
    field.update(t * .35f)
    drawImage(field.image, srcOffset = IntOffset.Zero, srcSize = IntSize(field.w, field.h),
        dstOffset = IntOffset.Zero, dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()),
        alpha = .55f, filterQuality = FilterQuality.Low)
    // 薄紗：讓背景退後
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .30f), Color.White.copy(alpha = .42f))))
    // 少量水面閃光
    repeat(6) { i ->
        val x = size.width * (((i * 37 + 11) % 97) / 97f)
        val y = size.height * (.05f + ((i * 23 + 5) % 41) / 41f * .3f)
        val tw = sin(t * 1.3f + i * 2.1f) * .5f + .5f
        drawSparkle(Offset(x, y), (3f + i % 3) * density, Color.White.copy(alpha = .55f * tw))
    }
}

private class BackdropStar(val x: Float, val y: Float, val r: Float, val a: Float, val twinkle: Boolean)

private fun backdropStars(): List<BackdropStar> {
    val rnd = Random(19)
    val list = ArrayList<BackdropStar>(240)
    repeat(110) {
        list += BackdropStar(rnd.nextFloat(), rnd.nextFloat(), .5f + rnd.nextFloat() * .7f,
            .25f + rnd.nextFloat() * .4f, rnd.nextInt(5) == 0)
    }
    // 由左下往右上的銀河帶
    repeat(130) {
        val u = rnd.nextFloat()
        val spread = (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 1.5f) * .16f
        list += BackdropStar((u + spread * .7f).coerceIn(0f, 1f), (1f - u * .95f + spread * .5f).coerceIn(0f, 1f),
            .4f + rnd.nextFloat() * .5f, .2f + rnd.nextFloat() * .35f, rnd.nextInt(8) == 0)
    }
    return list
}

private fun DrawScope.drawNightBackdrop(stars: List<BackdropStar>, t: Float) {
    drawRect(Brush.verticalGradient(listOf(HoshioPalette.NightTop, HoshioPalette.NightUpper,
        HoshioPalette.NightLower, HoshioPalette.NightBottom)))
    val w = size.width
    val h = size.height
    drawCircle(Brush.radialGradient(listOf(HoshioPalette.NightNebulaCool.copy(alpha = .12f), Color.Transparent),
        center = Offset(w * .25f, h * .35f), radius = w * .9f), w * .9f, Offset(w * .25f, h * .35f))
    drawCircle(Brush.radialGradient(listOf(HoshioPalette.NightNebulaWarm.copy(alpha = .10f), Color.Transparent),
        center = Offset(w * .8f, h * .7f), radius = w * .8f), w * .8f, Offset(w * .8f, h * .7f))
    stars.forEachIndexed { i, s ->
        val tw = if (s.twinkle) .5f + .5f * sin(t * (1.1f + (i % 4) * .25f) + i) else 1f
        drawCircle(HoshioPalette.StarWarm.copy(alpha = s.a * tw), s.r * density, Offset(s.x * w, s.y * h))
    }
    // 暖色四芒星
    listOf(Offset(.18f, .14f) to 6f, Offset(.82f, .22f) to 4.5f, Offset(.66f, .58f) to 3.5f,
        Offset(.28f, .72f) to 4f, Offset(.9f, .86f) to 3f).forEachIndexed { i, (p, r) ->
        val tw = .55f + .45f * sin(t * 1.4f + i * 1.9f)
        val pos = Offset(p.x * w, p.y * h)
        drawCircle(HoshioPalette.StarGlow.copy(alpha = .10f * tw), r * 1.6f * density, pos)
        drawSparkle(pos, r * density, HoshioPalette.StarWarm.copy(alpha = .8f * tw))
    }
    // 薄紗：降低對比，讓泡泡和文字更清楚
    drawRect(Color(0xFF11131B).copy(alpha = .22f))
}
