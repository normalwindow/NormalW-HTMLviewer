package xyz.normalwindow.htmlviewer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    inversePrimary = InversePrimaryLight,
    scrim = ScrimLight
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    inversePrimary = InversePrimaryDark,
    scrim = ScrimDark
)

/**
 * 全局 Material 3 主题。
 * - 自定义主题色优先(seedColor != null,用户指定种子色生成配色);
 * - Android 12+ 默认启用动态取色(Material You),跟随壁纸;
 * - 旧系统使用内置靛蓝系配色;
 * - 明暗模式跟随系统,可在设置中覆盖。
 */
@Composable
fun HTMLViewerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    seedColor: Color? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        seedColor != null ->
            if (darkTheme) seedDarkColorScheme(seedColor) else seedLightColorScheme(seedColor)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/** 种子色与目标色混合(fraction=0 保持种子色,1 完全目标色) */
private fun blend(seed: Color, target: Color, fraction: Float): Color = Color(
    red = seed.red + (target.red - seed.red) * fraction,
    green = seed.green + (target.green - seed.green) * fraction,
    blue = seed.blue + (target.blue - seed.blue) * fraction,
    alpha = 1f
)

/** 种子色上可读的前景色:按亮度选黑/白(WCAG 对比度兜底) */
private fun onColorFor(seed: Color): Color =
    if (seed.luminance() > 0.5f) Color.Black else Color.White

/** 由用户种子色派生的浅色配色(仅覆盖主色系,其余沿用默认基线) */
private fun seedLightColorScheme(seed: Color) = lightColorScheme(
    primary = seed,
    onPrimary = onColorFor(seed),
    primaryContainer = blend(seed, Color.White, 0.82f),
    onPrimaryContainer = blend(seed, Color.Black, 0.7f),
    secondary = blend(seed, Color.Black, 0.12f),
    secondaryContainer = blend(seed, Color.White, 0.78f),
    onSecondaryContainer = blend(seed, Color.Black, 0.66f),
    tertiary = blend(seed, Color.White, 0.4f),
    tertiaryContainer = blend(seed, Color.White, 0.85f),
    onTertiaryContainer = blend(seed, Color.Black, 0.62f),
    surfaceContainerLow = blend(seed, Color.White, 0.92f),
    surfaceContainer = blend(seed, Color.White, 0.88f),
    surfaceContainerHigh = blend(seed, Color.White, 0.84f),
    outlineVariant = blend(seed, Color.White, 0.8f)
)

/** 由用户种子色派生的深色配色 */
private fun seedDarkColorScheme(seed: Color) = darkColorScheme(
    primary = blend(seed, Color.White, 0.25f),
    onPrimary = onColorFor(blend(seed, Color.White, 0.25f)),
    primaryContainer = blend(seed, Color.Black, 0.6f),
    onPrimaryContainer = blend(seed, Color.White, 0.25f),
    secondary = blend(seed, Color.White, 0.55f),
    secondaryContainer = blend(seed, Color.Black, 0.72f),
    onSecondaryContainer = blend(seed, Color.White, 0.3f),
    tertiary = blend(seed, Color.White, 0.68f),
    tertiaryContainer = blend(seed, Color.Black, 0.68f),
    onTertiaryContainer = blend(seed, Color.White, 0.38f),
    surfaceContainerLow = blend(seed, Color.Black, 0.88f),
    surfaceContainer = blend(seed, Color.Black, 0.84f),
    surfaceContainerHigh = blend(seed, Color.Black, 0.8f),
    outlineVariant = blend(seed, Color.Black, 0.72f)
)
