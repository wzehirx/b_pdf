package io.github.wzehirx.bpdf.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

internal val LightColors = lightColorScheme(
    primary = BrandRed, onPrimary = BrandOnRed,
    primaryContainer = BrandRedContainer, onPrimaryContainer = BrandOnRedContainer,
    secondary = BrandSecondary, onSecondary = BrandOnRed,
    secondaryContainer = BrandSecondaryContainer, onSecondaryContainer = BrandOnSecondaryContainer,
    tertiary = BrandTertiary, onTertiary = BrandOnRed,
    tertiaryContainer = BrandTertiaryContainer, onTertiaryContainer = BrandOnTertiaryContainer,
    background = SurfaceLight, onBackground = OnSurfaceLight,
    surface = SurfaceLight, onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight, onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight, outlineVariant = OutlineVariantLight,
    surfaceContainerLowest = ContainerLowestLight, surfaceContainerLow = ContainerLowLight,
    surfaceContainer = ContainerLight, surfaceContainerHigh = ContainerHighLight,
    surfaceContainerHighest = ContainerHighestLight,
    error = ErrorLight, onError = BrandOnRed,
    errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
)

internal val DarkColors = darkColorScheme(
    primary = BrandRedDark, onPrimary = BrandOnRedDark,
    primaryContainer = BrandRedContainerDark, onPrimaryContainer = BrandOnRedContainerDark,
    secondary = BrandSecondaryDark, onSecondary = Color(0xFF442926),
    secondaryContainer = BrandSecondaryContainerDark, onSecondaryContainer = BrandOnSecondaryContainerDark,
    tertiary = BrandTertiaryDark, onTertiary = Color(0xFF3B2F04),
    tertiaryContainer = BrandTertiaryContainerDark, onTertiaryContainer = BrandOnTertiaryContainerDark,
    background = SurfaceDark, onBackground = OnSurfaceDark,
    surface = SurfaceDark, onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark, onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark, outlineVariant = OutlineVariantDark,
    surfaceContainerLowest = ContainerLowestDark, surfaceContainerLow = ContainerLowDark,
    surfaceContainer = ContainerDark, surfaceContainerHigh = ContainerHighDark,
    surfaceContainerHighest = ContainerHighestDark,
    error = ErrorDark, onError = OnErrorDark,
    errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
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
    surfaceContainerLowest = HcWhite, surfaceContainerLow = HcGreyLight, surfaceContainer = HcGreyLight,
    surfaceContainerHigh = HcGreyLight, surfaceContainerHighest = HcGreyLight,
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
    surfaceContainerLowest = HcBlack, surfaceContainerLow = HcGreyDark, surfaceContainer = HcGreyDark,
    surfaceContainerHigh = HcGreyDark, surfaceContainerHighest = HcGreyDark,
    error = HcErrorDark, onError = HcBlack,
    errorContainer = HcGreyDark, onErrorContainer = HcErrorDark,
    outline = HcWhite, outlineVariant = HcWhite,
)

/**
 * The brand scheme (light or dark) on every device; [dynamicColor] (wallpaper colors, Android 12+) is off by default so
 * the app looks the same for everyone. [highContrast] replaces either with a 7:1 palette.
 */
@Composable
fun BPdfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
