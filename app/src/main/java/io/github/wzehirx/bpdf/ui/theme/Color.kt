package io.github.wzehirx.bpdf.ui.theme

import androidx.compose.ui.graphics.Color

// Fallback brand palette (used below API 31). Text/background pairs meet WCAG AA (>= 4.5:1).
val BrandRed = Color(0xFFC62828) // white on this: 5.6:1
val BrandOnRed = Color(0xFFFFFFFF)
val BrandRedContainer = Color(0xFFFFDAD6)
val BrandOnRedContainer = Color(0xFF410002) // 14:1 on container

val BrandRedDark = Color(0xFFFFB4AB) // dark on this: 9:1
val BrandOnRedDark = Color(0xFF690005)
val BrandRedContainerDark = Color(0xFF93000A)
val BrandOnRedContainerDark = Color(0xFFFFDAD6) // 8:1 on container

val SurfaceLight = Color(0xFFFFFBFF)
val OnSurfaceLight = Color(0xFF201A19) // 16:1
val SurfaceDark = Color(0xFF201A19)
val OnSurfaceDark = Color(0xFFEDE0DD) // 12:1

// High-contrast palettes: every text/background pair is at least 7:1 (WCAG AAA); see HighContrastColorsTest.
val HcBlack = Color(0xFF000000)
val HcWhite = Color(0xFFFFFFFF)
val HcRedLight = Color(0xFF8A0000) // white on this: 11:1
val HcRedDark = Color(0xFFFFB3AB) // black on this: 12:1
val HcGreyLight = Color(0xFFE6E6E6)
val HcGreyDark = Color(0xFF2A2A2A)
val HcBlueLight = Color(0xFF00267A)
val HcBlueDark = Color(0xFFB8CCFF)
val HcErrorLight = Color(0xFF8A0000)
val HcErrorDark = Color(0xFFFFB3AB)
