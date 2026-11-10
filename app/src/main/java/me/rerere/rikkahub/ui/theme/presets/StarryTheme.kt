package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetTheme

/** 星汐: 星空主题，配合图示的夜空蓝紫与星星的金色 */
private val starrySeed = CustomTheme(
    id = "hoshio_starry",
    name = "星空",
    primaryColorArgb = 0xFF5B4FC4,
    secondaryColorArgb = 0xFF3D4483,
    tertiaryColorArgb = 0xFFE8B54A,
)

val StarryThemePreset by lazy {
    PresetTheme(
        id = "hoshio_starry",
        name = { Text("星空") },
        standardLight = starrySeed.generateColorScheme(dark = false),
        standardDark = starrySeed.generateColorScheme(dark = true),
    )
}
