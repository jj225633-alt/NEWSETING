package me.rerere.rikkahub.ui.pages.hoshio.splash

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.theme.presets.HoshioPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** 開場動畫總長：線條描繪 → 拱窗場景浮現 → 文字與星光。之後由外層決定何時淡出。 */
internal const val HOSHIO_INTRO_MS = 3500

// 海報座標系（270 × 540），繪製時等比縮放置中。
private const val FW = 270f
private const val FH = 540f
private const val AX = 65f
private const val AY = 110f
private const val AW = 140f
private const val AH = 280f
private const val AR = 70f

/**
 * 星汐開屏：日系編輯海報風。白天是拱窗裡的淺海焦散光，夜晚是低彩度的星空銀河。
 * 只負責畫面；何時結束、逾時與生命週期都由 OrbisStartupCover 管理。
 */
@Composable
internal fun HoshioSplash(
    dark: Boolean,
    animate: Boolean,
    slowLoading: Boolean,
    onSkip: () -> Unit,
    onIntroFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (animate) progress.animateTo(1f, tween(HOSHIO_INTRO_MS, easing = LinearEasing)) else progress.snapTo(1f)
        onIntroFinished()
    }
    val time = remember { mutableFloatStateOf(0f) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
        }
    }
    val art = remember(dark) { HoshioArt(dark) }
    val paper = if (dark) Color(0xFF0E1017) else Color(0xFFF1EFE8)
    Box(
        modifier.fillMaxSize()
            .pointerInput(onSkip) { detectTapGestures { onSkip() } }
            .semantics { contentDescription = "星汐" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(paper)
            val s = min(size.width / FW, size.height / FH)
            val ox = (size.width - FW * s) / 2f
            val oy = (size.height - FH * s) / 2f
            withTransform({
                translate(ox, oy)
                scale(s, s, pivot = Offset.Zero)
            }) {
                art.draw(this, progress.value, time.floatValue)
            }
        }
        if (slowLoading) {
            Text(
                "載入比平時稍久，點一下畫面即可進入",
                color = (if (dark) HoshioPalette.NightInk else HoshioPalette.DayInk).copy(alpha = .7f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            )
        }
    }
}

private fun seg(p: Float, from: Float, to: Float): Float {
    val x = ((p - from) / (to - from)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private class HoshioArt(private val dark: Boolean) {
    private val ink = if (dark) HoshioPalette.NightInk else HoshioPalette.DayInk
    private val line = if (dark) HoshioPalette.Gold else HoshioPalette.DayAccent
    private val sparkle = if (dark) HoshioPalette.StarWarm else Color(0xFFFFFFFF)
    private val accentStar = if (dark) HoshioPalette.Gold else HoshioPalette.DayAccent

    private val arch = archPath(AX, AY, AW, AH, AR)
    private val outerArch = archPath(AX - 6f, AY - 6f, AW + 12f, AH + 6f, AR + 6f)
    private val orbit1 = orbitPath(rx = 128f, ry = 40f, deg = -14f)
    private val orbit2 = orbitPath(rx = 118f, ry = 58f, deg = 10f)
    private val decoPaths = listOf(outerArch, arch, orbit1, orbit2,
        Path().apply { moveTo(26f, 60f); lineTo(26f, 500f) },
        Path().apply { moveTo(26f, 430f); lineTo(250f, 430f) },
        circlePath(232f, 62f, 12f), circlePath(232f, 62f, 20f), circlePath(232f, 62f, 28f),
    )
    private val measure = PathMeasure()
    private val partial = Path()

    private val caustics = if (dark) null else CausticField()
    private val stars = if (dark) starField() else emptyList()
    private val outsideSparkles = listOf(
        Triple(48f, 96f, 5f), Triple(238f, 140f, 4f), Triple(226f, 360f, 6f),
        Triple(44f, 330f, 3.5f), Triple(250f, 300f, 3f), Triple(150f, 72f, 3f),
    )

    private val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = serif
        isSubpixelText = true
        isLinearText = true
    }

    fun draw(scope: DrawScope, p: Float, t: Float) = with(scope) {
        val lineP = seg(p, 0f, .42f)
        val sceneA = seg(p, .22f, .6f)
        val textA = seg(p, .5f, .82f)
        val sparkA = seg(p, .62f, 1f)

        // 1. 拱窗裡的場景
        if (sceneA > 0f) clipPath(arch) {
            if (dark) drawNight(sceneA, t) else drawDay(sceneA, t)
        }

        // 2. 線條裝飾：依序描繪
        val stroke = Stroke(.55f)
        decoPaths.forEachIndexed { i, path ->
            val local = seg(lineP, i * .06f, .55f + i * .05f)
            if (local <= 0f) return@forEachIndexed
            measure.setPath(path, false)
            partial.reset()
            measure.getSegment(0f, measure.length * local, partial, true)
            val dashed = path === orbit2
            drawPath(partial, line.copy(alpha = if (i <= 1) .75f else .45f),
                style = if (dashed) Stroke(.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
                else stroke)
        }

        // 3. 軌道上的光點
        if (lineP >= 1f) {
            val a = (t * .55f + 2.2f)
            val local = Offset(128f * cos(a), 40f * sin(a))
            val pos = rotateAround(local, -14f) + Offset(135f, 260f)
            drawCircle(line.copy(alpha = .18f * textA), 5f, pos)
            drawCircle(line.copy(alpha = .95f * textA), 1.6f, pos)
        }

        // 4. 星光與八芒星
        outsideSparkles.forEachIndexed { i, (x, y, r) ->
            val tw = .65f + .35f * sin(t * 2.1f + i * 1.7f)
            drawSparkle(Offset(x, y), r, (if (dark) sparkle else line).copy(alpha = .8f * sparkA * tw))
        }
        drawEightStar(Offset(205f, 112f), 9f * (.8f + .2f * sparkA), accentStar.copy(alpha = sparkA))

        // 5. 文字
        if (textA > 0f) drawTexts(textA, t)
    }

    private fun DrawScope.drawDay(alpha: Float, t: Float) {
        val area = Rect(AX, AY, AX + AW, AY + AH)
        drawRect(Brush.verticalGradient(listOf(HoshioPalette.SeaTop, HoshioPalette.SeaMid, Color(0xFFD9ECE4), HoshioPalette.Sand),
            startY = area.top, endY = area.bottom), area.topLeft, area.size, alpha = alpha)
        // 斜射的陽光
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .35f), Color.Transparent),
            start = Offset(area.right, area.top), end = Offset(area.left + 30f, area.top + 170f)),
            area.topLeft, area.size, alpha = alpha)
        caustics?.let { field ->
            field.update(t * 1.4f)
            drawImage(field.image, srcOffset = IntOffset.Zero, srcSize = IntSize(field.w, field.h),
                dstOffset = IntOffset(AX.toInt(), AY.toInt()), dstSize = IntSize(AW.toInt(), AH.toInt()),
                alpha = alpha * .9f, filterQuality = FilterQuality.Low)
        }
        // 水面閃光
        repeat(7) { i ->
            val x = AX + 18f + ((i * 37) % 104)
            val y = AY + 24f + ((i * 23) % 60)
            val tw = (sin(t * 3.1f + i * 2.3f) * .5f + .5f)
            drawSparkle(Offset(x, y), 2.2f + (i % 3), Color.White.copy(alpha = alpha * tw * .9f))
        }
    }

    private fun DrawScope.drawNight(alpha: Float, t: Float) {
        val area = Rect(AX, AY, AX + AW, AY + AH)
        drawRect(Brush.verticalGradient(listOf(HoshioPalette.NightTop, HoshioPalette.NightUpper, HoshioPalette.NightLower, HoshioPalette.NightBottom),
            startY = area.top, endY = area.bottom), area.topLeft, area.size, alpha = alpha)
        drawCircle(Brush.radialGradient(listOf(HoshioPalette.NightNebulaCool.copy(alpha = .16f), Color.Transparent),
            center = Offset(110f, 210f), radius = 90f), 90f, Offset(110f, 210f), alpha = alpha)
        drawCircle(Brush.radialGradient(listOf(HoshioPalette.NightNebulaWarm.copy(alpha = .14f), Color.Transparent),
            center = Offset(172f, 320f), radius = 80f), 80f, Offset(172f, 320f), alpha = alpha)
        // 銀河帶
        rotate(-52f, pivot = Offset(135f, 250f)) {
            val band = Size(320f, 46f)
            drawOval(Brush.radialGradient(listOf(Color(0xFFE9E2F0).copy(alpha = .10f), Color.Transparent),
                center = Offset(135f, 250f), radius = 160f),
                topLeft = Offset(135f - band.width / 2f, 250f - band.height / 2f), size = band, alpha = alpha)
        }
        // 星空在拱窗裡緩緩旋轉，星星明顯地閃
        rotate(t * 7f, pivot = Offset(AX + AW / 2f, AY + AH / 2f)) {
            stars.forEachIndexed { i, st ->
                val tw = if (st.twinkle) (.5f + .5f * sin(t * (3.2f + (i % 5) * .8f) + i * 1.3f)).let { .15f + .85f * it * it } else 1f
                if (st.twinkle && tw > .8f) drawCircle(HoshioPalette.StarGlow.copy(alpha = .22f * tw * alpha), st.r * 3f, Offset(st.x, st.y))
                drawCircle(HoshioPalette.StarWarm.copy(alpha = (st.a * 1.2f).coerceAtMost(1f) * tw * alpha), st.r, Offset(st.x, st.y))
            }
        }
        // 流星
        val ph = ((t - .9f) % 1.6f) / .7f
        if (t > .9f && ph in 0f..1f) {
            val n = ((t - .9f) / 1.6f).toInt()
            val sx = AX + AW * (.55f + (n % 3) * .15f)
            val sy = AY + AH * (.12f + (n % 2) * .12f)
            val len = 70f
            val head = Offset(sx - .8f * len * ph, sy + .6f * len * ph)
            val tail = Offset(head.x + .8f * len * .4f, head.y - .6f * len * .4f)
            val fade = sin(ph * Math.PI.toFloat()) * alpha
            drawLine(Brush.linearGradient(listOf(Color.Transparent, HoshioPalette.StarWarm.copy(alpha = .95f * fade)), start = tail, end = head),
                tail, head, strokeWidth = .8f)
            drawCircle(HoshioPalette.StarWarm.copy(alpha = fade), 1f, head)
        }
        // 暖色四芒星
        listOf(Offset(98f, 162f) to 6f, Offset(168f, 214f) to 4.5f, Offset(120f, 300f) to 3.5f,
            Offset(182f, 150f) to 3f, Offset(88f, 360f) to 3.5f).forEachIndexed { i, (pos, r) ->
            val tw = .35f + .65f * sin(t * 3.4f + i * 1.9f).let { it * it }
            drawCircle(HoshioPalette.StarGlow.copy(alpha = .22f * alpha * tw), r * 1.6f, pos)
            drawSparkle(pos, r, HoshioPalette.StarWarm.copy(alpha = alpha * tw))
        }
    }

    private fun DrawScope.drawTexts(a: Float, t: Float) = drawIntoCanvas { c ->
        val canvas = c.nativeCanvas
        val inkArgb = ink.copy(alpha = a).toArgb()
        val lineArgb = line.copy(alpha = a * .9f).toArgb()
        textPaint.textAlign = android.graphics.Paint.Align.CENTER
        textPaint.letterSpacing = 0f

        // 直排大字
        textPaint.color = inkArgb
        textPaint.textSize = 30f
        val big = if (dark) "銀河" else "淺海"
        big.forEachIndexed { i, ch -> canvas.drawText(ch.toString(), 238f, 214f + i * 36f, textPaint) }
        textPaint.textSize = 6f
        textPaint.letterSpacing = .2f
        textPaint.color = lineArgb
        canvas.drawText("N°01", 238f, 176f, textPaint)

        // 左上角刊頭
        textPaint.textAlign = android.graphics.Paint.Align.LEFT
        textPaint.textSize = 6.5f
        textPaint.letterSpacing = .3f
        canvas.drawText("HOSHIO", 36f, 52f, textPaint)
        textPaint.color = ink.copy(alpha = a * .6f).toArgb()
        canvas.drawText(if (dark) "NIGHT EDITION" else "DAY EDITION", 36f, 62f, textPaint)

        // 品牌
        textPaint.color = inkArgb
        textPaint.textSize = 34f
        textPaint.letterSpacing = .08f
        canvas.drawText("星汐", 36f, 474f, textPaint)
        textPaint.textSize = 7.5f
        textPaint.letterSpacing = .55f
        textPaint.color = lineArgb
        canvas.drawText("HOSHIO", 38f, 492f, textPaint)

        // 頁尾
        textPaint.textSize = 5.2f
        textPaint.letterSpacing = .25f
        textPaint.color = ink.copy(alpha = a * .55f).toArgb()
        canvas.drawText("N° 01 — HOSHIO ${if (dark) "NIGHT" else "DAY"} / ${if (dark) "STARRY TIDE" else "SHALLOW LIGHT"}", 36f, 524f, textPaint)

        // 旋轉的環形文字
        val ring = android.graphics.Path().apply { addCircle(210f, 470f, 26f, android.graphics.Path.Direction.CW) }
        canvas.save()
        canvas.rotate(t * 10f, 210f, 470f)
        textPaint.textAlign = android.graphics.Paint.Align.LEFT
        textPaint.textSize = 5.6f
        textPaint.letterSpacing = .32f
        textPaint.color = lineArgb
        canvas.drawTextOnPath(if (dark) "HOSHIO · STARRY · TIDE · NIGHT · " else "HOSHIO · SHALLOW · LIGHT · SEA · ",
            ring, 0f, 0f, textPaint)
        canvas.restore()
        canvas.drawCircle(210f, 470f, 1.4f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = lineArgb })
    }
}

internal fun DrawScope.drawSparkle(c: Offset, r: Float, color: Color) {
    if (color.alpha <= .01f) return
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x, c.y, c.x + r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - r)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.drawEightStar(c: Offset, r: Float, color: Color) {
    if (color.alpha <= .01f) return
    drawCircle(color.copy(alpha = color.alpha * .15f), r * .9f, c)
    rotate(45f, pivot = c) { drawSparkle(c, r * .6f, color.copy(alpha = color.alpha * .8f)) }
    drawSparkle(c, r, color)
}

private fun archPath(x: Float, y: Float, w: Float, h: Float, r: Float) = Path().apply {
    moveTo(x, y + h)
    lineTo(x, y + r)
    arcTo(Rect(x, y, x + w, y + 2 * r), 180f, 180f, false)
    lineTo(x + w, y + h)
    close()
}

private fun circlePath(cx: Float, cy: Float, r: Float) = Path().apply {
    addOval(Rect(cx - r, cy - r, cx + r, cy + r))
}

private fun rotateAround(p: Offset, deg: Float): Offset {
    val rad = deg * PI.toFloat() / 180f
    return Offset(p.x * cos(rad) - p.y * sin(rad), p.x * sin(rad) + p.y * cos(rad))
}

private fun orbitPath(rx: Float, ry: Float, deg: Float) = Path().apply {
    val steps = 96
    for (i in 0..steps) {
        val a = -PI.toFloat() / 2f + i * 2f * PI.toFloat() / steps
        val pt = rotateAround(Offset(rx * cos(a), ry * sin(a)), deg) + Offset(135f, 260f)
        if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
    }
}

private class Star(val x: Float, val y: Float, val r: Float, val a: Float, val twinkle: Boolean)

/** 固定種子的星空：一般星點加上沿對角線集中的銀河帶。 */
private fun starField(): List<Star> {
    val rnd = Random(7)
    val list = ArrayList<Star>(260)
    // 範圍比拱窗大，旋轉時四角也有星星
    val cx = AX + AW / 2f
    val cy = AY + AH / 2f
    val half = AH * .62f
    repeat(150) {
        list += Star(cx - half + rnd.nextFloat() * half * 2f, cy - half + rnd.nextFloat() * half * 2f,
            .25f + rnd.nextFloat() * .5f, .35f + rnd.nextFloat() * .5f, rnd.nextInt(2) == 0)
    }
    repeat(170) {
        val u = rnd.nextFloat()
        val spread = (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 1.5f) * 22f
        // 銀河帶從左下延伸到右上
        val bx = AX + u * AW + spread * .62f
        val by = AY + AH - u * AH * .92f + spread * .78f
        list += Star(bx, by, .2f + rnd.nextFloat() * .35f, .25f + rnd.nextFloat() * .45f, rnd.nextInt(3) == 0)
    }
    return list
}

/** 低解析度 Worley（F2 − F1）焦散網格，每幀更新後放大繪製。 */
internal class CausticField(val w: Int = 56, val h: Int = 112) {
    private val bitmap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val image = bitmap.asImageBitmap()
    private val px = IntArray(w * h)
    private val n = 22
    private val rnd = Random(11)
    private val bx = FloatArray(n) { rnd.nextFloat() }
    private val by = FloatArray(n) { rnd.nextFloat() * (h.toFloat() / w) }
    private val phase = FloatArray(n) { rnd.nextFloat() * 6.28f }
    private val speed = FloatArray(n) { .5f + rnd.nextFloat() * .7f }
    private val ax = FloatArray(n)
    private val ay = FloatArray(n)
    private var lastT = -1f

    fun update(t: Float) {
        if (t == lastT) return
        lastT = t
        for (i in 0 until n) {
            ax[i] = bx[i] + .07f * sin(t * speed[i] + phase[i])
            ay[i] = by[i] + .07f * cos(t * speed[i] * .8f + phase[i] * 1.3f)
        }
        val inv = 1f / w
        for (y in 0 until h) {
            val fy = y * inv
            val depth = 1f - .6f * (y.toFloat() / h)
            for (x in 0 until w) {
                val fx = x * inv
                var f1 = 9f
                var f2 = 9f
                for (i in 0 until n) {
                    val dx = fx - ax[i]
                    val dy = fy - ay[i]
                    val d = dx * dx + dy * dy
                    if (d < f1) { f2 = f1; f1 = d } else if (d < f2) f2 = d
                }
                var v = 1f - (sqrt(f2) - sqrt(f1)) / .05f
                if (v < 0f) v = 0f
                v *= v
                val a = (v * .85f * depth * 255f).toInt().coerceIn(0, 255)
                px[y * w + x] = (a shl 24) or 0xFFFFFF
            }
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }
}
