package io.github.wzehirx.bpdf.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

private val Base = Typography()

/**
 * Material 3 type scale; sizes stay in sp so they follow the system font scale. Titles are a little heavier so that
 * headings, section titles and list titles read as one hierarchy: headlineSmall (screen title), titleMedium (section
 * titles), titleSmall (list item titles), bodyLarge/bodyMedium (text), bodySmall (supporting text).
 */
val BPdfTypography = Typography(
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
)
