package xyz.normalwindow.htmlviewer.ui.theme

import android.app.WallpaperManager
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import xyz.normalwindow.htmlviewer.data.settings.ColorStyle

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
 * - 选定具体配色方案时(colorStyle != SYSTEM):以种子色(自定义主题色优先,
 *   否则壁纸主色,再退默认靛蓝)经 material-color-utilities 算法生成对应色调方案,
 *   整套界面(按钮/容器/高亮/控制台等)随主题色变化;
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
    colorStyle: ColorStyle = ColorStyle.SYSTEM,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // 未选自定义主题色时,配色方案使用壁纸主色作为种子(Android 8.1+ 支持,失败退默认色)
    val wallpaperSeed = remember(context) { wallpaperSeedColor(context) }
    val colorScheme = when {
        colorStyle != ColorStyle.SYSTEM -> {
            val seed = seedColor ?: wallpaperSeed ?: DefaultSeedColor
            dynamicColorScheme(
                primary = seed,
                isDark = darkTheme,
                isAmoled = false,
                style = colorStyle.paletteStyle()
            )
        }
        seedColor != null ->
            if (darkTheme) seedDarkColorScheme(seedColor) else seedLightColorScheme(seedColor)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
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

/** 配色方案 → material-color-utilities 色调方案 */
private fun ColorStyle.paletteStyle(): PaletteStyle = when (this) {
    ColorStyle.SYSTEM -> PaletteStyle.TonalSpot // 不可达,仅占位
    ColorStyle.TONAL_SPOT -> PaletteStyle.TonalSpot
    ColorStyle.NEUTRAL -> PaletteStyle.Neutral
    ColorStyle.VIBRANT -> PaletteStyle.Vibrant
    ColorStyle.EXPRESSIVE -> PaletteStyle.Expressive
    ColorStyle.RAINBOW -> PaletteStyle.Rainbow
    ColorStyle.FRUIT_SALAD -> PaletteStyle.FruitSalad
    ColorStyle.MONOCHROME -> PaletteStyle.Monochrome
    ColorStyle.FIDELITY -> PaletteStyle.Fidelity
}

/** 未选自定义主题色时的默认种子色(与内置浅色主题主色一致) */
private val DefaultSeedColor = Color(0xFF4355B9)

/** 壁纸主色(配色方案种子;API 27+ 支持,失败返回 null 由调用方退默认色) */
private fun wallpaperSeedColor(context: android.content.Context): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    return runCatching {
        WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor
            ?.let { Color(it.toArgb()) }
    }.getOrNull()
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
