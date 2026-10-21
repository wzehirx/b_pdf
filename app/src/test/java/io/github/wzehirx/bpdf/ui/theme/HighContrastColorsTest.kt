package io.github.wzehirx.bpdf.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class HighContrastColorsTest {
    private fun ratio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun pairs(c: ColorScheme) = mapOf(
        "onBackground/background" to (c.onBackground to c.background),
        "onSurface/surface" to (c.onSurface to c.surface),
        "onSurfaceVariant/surfaceVariant" to (c.onSurfaceVariant to c.surfaceVariant),
        "onSurfaceVariant/surface" to (c.onSurfaceVariant to c.surface),
        "onPrimary/primary" to (c.onPrimary to c.primary),
        "onPrimaryContainer/primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
        "onSecondary/secondary" to (c.onSecondary to c.secondary),
        "onSecondaryContainer/secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
        "onTertiary/tertiary" to (c.onTertiary to c.tertiary),
        "onTertiaryContainer/tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
        "onError/error" to (c.onError to c.error),
        "onErrorContainer/errorContainer" to (c.onErrorContainer to c.errorContainer),
        "onSurface/surfaceContainer" to (c.onSurface to c.surfaceContainer),
        "onSurface/surfaceContainerHighest" to (c.onSurface to c.surfaceContainerHighest),
        // Text and icons coloured with the accent sit directly on the surface.
        "primary/background" to (c.primary to c.background),
        "error/background" to (c.error to c.background),
        "outline/background" to (c.outline to c.background),
    )

    private fun assertAtLeast7(name: String, c: ColorScheme) =
        pairs(c).forEach { (pair, colors) ->
            val r = ratio(colors.first, colors.second)
            assertTrue("$name $pair is ${"%.2f".format(r)}:1, needs 7:1", r >= 7.0)
        }

    @Test
    fun highContrastLight_meetsAAA() = assertAtLeast7("light", HighContrastLightColors)

    @Test
    fun highContrastDark_meetsAAA() = assertAtLeast7("dark", HighContrastDarkColors)
}
