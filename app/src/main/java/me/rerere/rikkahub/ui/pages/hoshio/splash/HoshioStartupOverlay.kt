package me.rerere.rikkahub.ui.pages.hoshio.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * 星汐开屏：App 启动时盖在最上面播一次（约 3.5 秒），点一下画面可以略过，播完淡出。
 * 转屏或重建画面不会再播一次。
 */
@Composable
fun HoshioStartupOverlay(content: @Composable () -> Unit) {
    var showing by rememberSaveable { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        content()
        if (showing) {
            val alpha = remember { Animatable(1f) }
            var done by remember { mutableStateOf(false) }
            LaunchedEffect(done) {
                if (done) {
                    alpha.animateTo(0f, tween(450))
                    showing = false
                }
            }
            Box(
                Modifier.fillMaxSize().zIndex(20f).graphicsLayer { this.alpha = alpha.value }.pointerInput(Unit) {
                    // 挡住底下的聊天画面，点击交给开屏处理
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
            ) {
                HoshioSplash(
                    dark = LocalDarkMode.current,
                    animate = true,
                    slowLoading = false,
                    onSkip = { done = true },
                    onIntroFinished = { done = true },
                )
            }
        }
    }
}
