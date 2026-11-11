package me.rerere.rikkahub.ui.pages.hoshio

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import me.rerere.rikkahub.ui.pages.hoshio.splash.LocalHoshioHaze
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * 星汐毛玻璃泡泡（移植自 orbis-tw）：圓角、半透明、上緣高光與細邊框。
 * 她的泡泡是深色低彩度（白天深青灰、夜晚深灰紫），他的泡泡是透亮的玻璃。
 * [opacity] 跟著設定裡的「氣泡不透明度」，以 1 為基準等比例縮放。
 */
@Composable
fun HoshioGlassBubble(
    user: Boolean,
    opacity: Float,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val dark = LocalDarkMode.current
    val scale = opacity.coerceIn(0.15f, 1f)
    val shape = RoundedCornerShape(
        topStart = 22.dp, topEnd = 22.dp,
        bottomEnd = if (user) 8.dp else 22.dp,
        bottomStart = if (user) 22.dp else 8.dp,
    )
    val fill: List<Color>
    val edge: Color
    val highlight: Color
    val text: Color
    when {
        user && dark -> {
            // 夜晚：深海藍帶一點星光金邊，和他的灰紫玻璃分得開
            fill = listOf(Color(0xFF2E5470).copy(alpha = .90f), Color(0xFF1F3B55).copy(alpha = .90f))
            edge = Color(0xFFD8C49A).copy(alpha = .35f); highlight = Color.White.copy(alpha = .10f)
            text = Color(0xFFF2EEE4)
        }
        user -> {
            fill = listOf(Color(0xFF3B5F69).copy(alpha = .84f), Color(0xFF2D4C55).copy(alpha = .84f))
            edge = Color.White.copy(alpha = .22f); highlight = Color.White.copy(alpha = .12f)
            text = Color(0xFFF0F5F3)
        }
        dark -> {
            fill = listOf(Color(0xFF262838).copy(alpha = .58f), Color(0xFF1A1C28).copy(alpha = .50f))
            edge = Color.White.copy(alpha = .12f); highlight = Color.White.copy(alpha = .07f)
            text = Color(0xFFE6E2D8)
        }
        else -> {
            fill = listOf(Color.White.copy(alpha = .62f), Color.White.copy(alpha = .40f))
            edge = Color.White.copy(alpha = .80f); highlight = Color.White.copy(alpha = .45f)
            text = Color(0xFF22454F)
        }
    }
    // 真正的背景模糊（Android 12+）：只模糊聊天背景那一層，舊手機維持半透明。
    val haze = LocalHoshioHaze.current
    val blur = haze != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(
        modifier = modifier
            .clip(shape)
            .then(if (blur) Modifier.hazeBlur(
                input = HazeInput.Sources(haze!!),
                style = HazeBlurStyle {
                    blurRadius(14.dp)
                    noiseFactor(0f)
                    colorEffects(emptyList())
                    fallbackColorEffect(HazeColorEffect.tint(fill.first()))
                },
            ) else Modifier)
            .background(Brush.linearGradient(fill.map { it.copy(alpha = (it.alpha * scale * if (blur) 1f else 1.15f).coerceIn(0f, 1f)) }))
            .drawBehind {
                // 上緣高光，做出玻璃的厚度感
                drawRect(
                    Brush.verticalGradient(listOf(highlight.copy(alpha = highlight.alpha * scale), Color.Transparent), endY = size.height * .45f)
                )
            }
            .border(0.8.dp, Brush.verticalGradient(listOf(edge.copy(alpha = edge.alpha * scale), edge.copy(alpha = edge.alpha * scale * .35f))), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides text) { content() }
    }
}
