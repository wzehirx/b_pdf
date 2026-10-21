package io.github.wzehirx.bpdf.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = BrandRed,
    onPrimary = BrandOnRed,
    primaryContainer = BrandRedContainer,
    onPrimaryContainer = BrandOnRedContainer,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = BrandRedDark,
    onPrimary = BrandOnRedDark,
    primaryContainer = BrandRedContainerDark,
    onPrimaryContainer = BrandOnRedContainerDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
)

internal val HighContrastLightColors = lightColorScheme(
    primary = HcRedLight, onPrimary = HcWhite,
    primaryContainer = HcGreyLight, onPrimaryContainer = HcBlack,
    secondary = HcBlueLight, onSecondary = HcWhite,
    secondaryContainer = HcGreyLight, onSecondaryContainer = HcBlack,
    tertiary = HcBlueLight, onTertiary = HcWhite,
    tertiaryContainer = HcGreyLight, onTertiaryContainer = HcBlack,
    background = HcWhite, onBackground = HcBlack,
    surface = HcWhite, onSurface = HcBlack,
    surfaceVariant = HcGreyLight, onSurfaceVariant = HcBlack,
    surfaceContainer = HcGreyLight, surfaceContainerHighest = HcGreyLight,
    error = HcErrorLight, onError = HcWhite,
    errorContainer = HcGreyLight, onErrorContainer = HcErrorLight,
    outline = HcBlack, outlineVariant = HcBlack,
)

internal val HighContrastDarkColors = darkColorScheme(
    primary = HcRedDark, onPrimary = HcBlack,
    primaryContainer = HcGreyDark, onPrimaryContainer = HcWhite,
    secondary = HcBlueDark, onSecondary = HcBlack,
    secondaryContainer = HcGreyDark, onSecondaryContainer = HcWhite,
    tertiary = HcBlueDark, onTertiary = HcBlack,
    tertiaryContainer = HcGreyDark, onTertiaryContainer = HcWhite,
    background = HcBlack, onBackground = HcWhite,
    surface = HcBlack, onSurface = HcWhite,
    surfaceVariant = HcGreyDark, onSurfaceVariant = HcWhite,
    surfaceContainer = HcGreyDark, surfaceContainerHighest = HcGreyDark,
    error = HcErrorDark, onError = HcBlack,
    errorContainer = HcGreyDark, onErrorContainer = HcErrorDark,
    outline = HcWhite, outlineVariant = HcWhite,
)

/** [highContrast] replaces the dynamic (wallpaper) and brand colors with a 7:1 palette. */
@Composable
fun BPdfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        highContrast -> if (darkTheme) HighContrastDarkColors else HighContrastLightColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = BPdfTypography, content = content)
}
