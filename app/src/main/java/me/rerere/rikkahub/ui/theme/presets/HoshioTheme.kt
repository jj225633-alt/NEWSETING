package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.theme.PresetTheme

/**
 * 星汐 Hoshio：白天是淺海光影（水青 → 白沙），夜晚是低彩度的深藍灰星空與暖金點綴。
 */
val HoshioThemePreset by lazy {
    PresetTheme(
        id = "hoshio",
        name = { Text("星汐") },
        standardLight = hoshioLightScheme,
        standardDark = hoshioDarkScheme,
    )
}

/** 開屏與之後的背景共用的色票。 */
object HoshioPalette {
    // 白天：淺海
    val SeaTop = Color(0xFF78D6DC)
    val SeaMid = Color(0xFFA9E3E0)
    val Sand = Color(0xFFEEECE4)
    val DayInk = Color(0xFF22454F)
    val DayAccent = Color(0xFF2F6F7C)

    // 夜晚：低彩度星空（偏灰的海軍藍，不要太藍）
    val NightTop = Color(0xFF0A0C14)
    val NightUpper = Color(0xFF141826)
    val NightLower = Color(0xFF1E1D2C)
    val NightBottom = Color(0xFF29253A)
    val NightNebulaCool = Color(0xFF6F8C99)
    val NightNebulaWarm = Color(0xFF7D6E92)
    val StarWarm = Color(0xFFFFECD7)
    val StarGlow = Color(0xFFFFD6B4)
    val Gold = Color(0xFFD8C49A)
    val NightInk = Color(0xFFE6E2D8)
}

private val hoshioLightScheme = lightColorScheme(
    primary = Color(0xFF2F6F7C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC6EAEC),
    onPrimaryContainer = Color(0xFF103E47),
    secondary = Color(0xFF4F6669),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E6E6),
    onSecondaryContainer = Color(0xFF243B3E),
    tertiary = Color(0xFF8A6D3F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E2C2),
    onTertiaryContainer = Color(0xFF3E2C0A),
    error = Color(0xFFB3423A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4F5F1),
    onBackground = Color(0xFF1C3138),
    surface = Color(0xFFF4F5F1),
    onSurface = Color(0xFF1C3138),
    surfaceVariant = Color(0xFFDCE6E4),
    onSurfaceVariant = Color(0xFF3F5257),
    outline = Color(0xFF6F8287),
    outlineVariant = Color(0xFFC0CDCC),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2B3B40),
    inverseOnSurface = Color(0xFFEAF1EF),
    inversePrimary = Color(0xFF93D2DC),
    surfaceDim = Color(0xFFD6DCD9),
    surfaceBright = Color(0xFFFBFBF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF2EF),
    surfaceContainer = Color(0xFFE8EEEB),
    surfaceContainerHigh = Color(0xFFE2E9E7),
    surfaceContainerHighest = Color(0xFFDCE4E2),
)

private val hoshioDarkScheme = darkColorScheme(
    primary = Color(0xFFD8C49A),
    onPrimary = Color(0xFF3A2F17),
    primaryContainer = Color(0xFF4A4232),
    onPrimaryContainer = Color(0xFFF2E3C2),
    secondary = Color(0xFFB6B9C8),
    onSecondary = Color(0xFF242733),
    secondaryContainer = Color(0xFF3A3C4D),
    onSecondaryContainer = Color(0xFFDDDFEA),
    tertiary = Color(0xFFA9C3C8),
    onTertiary = Color(0xFF1B3237),
    tertiaryContainer = Color(0xFF34494D),
    onTertiaryContainer = Color(0xFFC9E2E6),
    error = Color(0xFFF0B4AC),
    onError = Color(0xFF561E19),
    errorContainer = Color(0xFF73332D),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF11131B),
    onBackground = Color(0xFFE6E2D8),
    surface = Color(0xFF11131B),
    onSurface = Color(0xFFE6E2D8),
    surfaceVariant = Color(0xFF353747),
    onSurfaceVariant = Color(0xFFC3C2CC),
    outline = Color(0xFF8D8C98),
    outlineVariant = Color(0xFF3F4050),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE6E2D8),
    inverseOnSurface = Color(0xFF2A2B33),
    inversePrimary = Color(0xFF6E5D35),
    surfaceDim = Color(0xFF11131B),
    surfaceBright = Color(0xFF363844),
    surfaceContainerLowest = Color(0xFF0B0D13),
    surfaceContainerLow = Color(0xFF181A24),
    surfaceContainer = Color(0xFF1C1E29),
    surfaceContainerHigh = Color(0xFF262834),
    surfaceContainerHighest = Color(0xFF31333F),
)
