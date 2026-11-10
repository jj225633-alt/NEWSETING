package me.rerere.rikkahub.ui.pages.hoshio.splash

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/** 星汐頁面背景的模糊來源；沒有提供時玻璃改用半透明（不模糊）。 */
val LocalHoshioHaze = compositionLocalOf<HazeState?> { null }

/** 液態玻璃的色彩參數（依使用者提供的玻璃參數調整，夜晚降低彩度）。 */
internal object HoshioGlassTokens {
    val DayFill = listOf(Color.White.copy(alpha = .62f), Color.White.copy(alpha = .28f))
    val NightFill = listOf(Color(0xFF5A5C70).copy(alpha = .30f), Color(0xFF161822).copy(alpha = .42f))
    val DayEdge = listOf(Color.White.copy(alpha = .85f), Color.White.copy(alpha = .25f))
    val NightEdge = listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .05f))
}

/**
 * 液態玻璃容器：背景模糊（Android 12+）、150° 漸層填色、上亮下暗的 1dp 邊框、
 * 上緣內側高光與一道斜向光澤。內容自行決定版面。
 */
@Composable
fun HoshioGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    blur: Dp = 16.dp,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = LocalDarkMode.current
    val haze = LocalHoshioHaze.current
    val fill = if (dark) HoshioGlassTokens.NightFill else HoshioGlassTokens.DayFill
    val edge = if (dark) HoshioGlassTokens.NightEdge else HoshioGlassTokens.DayEdge
    val canBlur = haze != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // 不能模糊時把底色加厚一點，維持可讀性。
    val boost = if (canBlur) 1f else 1.35f
    val tint = (if (strong) fill.map { it.copy(alpha = (it.alpha * 1.3f).coerceAtMost(.92f)) } else fill)
        .map { it.copy(alpha = (it.alpha * boost).coerceAtMost(.92f)) }
    Box(
        modifier
            .clip(shape)
            .then(if (canBlur) Modifier.hazeBlur(
                input = HazeInput.Sources(haze!!),
                style = HazeBlurStyle {
                    blurRadius(blur)
                    noiseFactor(0f)
                    colorEffects(emptyList())
                    fallbackColorEffect(HazeColorEffect.tint(tint.first()))
                },
            ) else Modifier)
            .background(Brush.linearGradient(tint, start = Offset.Zero, end = Offset(500f, 866f))) // 約 150° 方向
            .drawWithContent {
                drawContent()
                // 斜向光澤
                drawRect(Brush.linearGradient(
                    0f to Color.Transparent,
                    .32f to Color.Transparent,
                    .42f to Color.White.copy(alpha = if (dark) .05f else .16f),
                    .52f to Color.Transparent,
                    1f to Color.Transparent,
                    start = Offset.Zero, end = Offset(size.width, size.height),
                ))
                // 上緣內側高光
                val y = 1.2.dp.toPx()
                drawLine(Brush.horizontalGradient(listOf(Color.Transparent,
                    Color.White.copy(alpha = if (dark) .22f else .85f), Color.Transparent)),
                    Offset(size.width * .08f, y), Offset(size.width * .92f, y), 1.dp.toPx())
            }
            .border(1.dp, Brush.verticalGradient(edge), shape),
        content = content,
    )
}
