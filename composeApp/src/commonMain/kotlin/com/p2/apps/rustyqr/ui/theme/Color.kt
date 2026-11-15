package com.p2.apps.rustyqr.ui.theme

import androidx.compose.ui.graphics.Color

// Rusty QR — Rust-Branded Dark palette
// Spec §1 (2026-04-11-ui-design.md)
// Palette tokens — internal to theme. Feature code must use MaterialTheme.colorScheme.*.

// ARGB values for the palette tokens below; each role keeps its own constant even when values coincide.
private object PaletteArgb {
    const val BACKGROUND = 0xFF1A1A1A
    const val SURFACE = 0xFF1E1E1E
    const val SURFACE_VARIANT = 0xFF2A2A2A
    const val PRIMARY = 0xFFF5A623
    const val ON_PRIMARY = 0xFF1A1A1A
    const val ON_BACKGROUND = 0xFFE8E8E8
    const val ON_SURFACE = 0xFF999999
    const val ERROR = 0xFFCF6679
    const val SURFACE_CONTAINER_LOWEST = 0xFF141414
    const val SURFACE_CONTAINER_LOW = 0xFF1C1C1C
    const val SURFACE_CONTAINER = 0xFF222222
    const val SURFACE_CONTAINER_HIGH = 0xFF2A2A2A
    const val SURFACE_CONTAINER_HIGHEST = 0xFF323232
    const val ON_SURFACE_VARIANT = 0xFF888888
    const val OUTLINE = 0xFF3A3A3A
    const val OUTLINE_VARIANT = 0xFF2D2D2D
    const val PRIMARY_CONTAINER = 0xFF4A3000
    const val ON_PRIMARY_CONTAINER = 0xFFF5A623
    const val INVERSE_SURFACE = 0xFFE8E8E8
    const val INVERSE_ON_SURFACE = 0xFF1A1A1A
    const val SCRIM = 0xFF000000
}

val Background = Color(PaletteArgb.BACKGROUND)
val Surface = Color(PaletteArgb.SURFACE)
val SurfaceVariant = Color(PaletteArgb.SURFACE_VARIANT)
val Primary = Color(PaletteArgb.PRIMARY)
val OnPrimary = Color(PaletteArgb.ON_PRIMARY)
val OnBackground = Color(PaletteArgb.ON_BACKGROUND)
val OnSurface = Color(PaletteArgb.ON_SURFACE)
val Error = Color(PaletteArgb.ERROR)

// MD3 surface tier — full container scale
val SurfaceContainerLowest = Color(PaletteArgb.SURFACE_CONTAINER_LOWEST)
val SurfaceContainerLow = Color(PaletteArgb.SURFACE_CONTAINER_LOW)
val SurfaceContainer = Color(PaletteArgb.SURFACE_CONTAINER)
val SurfaceContainerHigh = Color(PaletteArgb.SURFACE_CONTAINER_HIGH)
val SurfaceContainerHighest = Color(PaletteArgb.SURFACE_CONTAINER_HIGHEST)

// Supplementary role tokens
val OnSurfaceVariant = Color(PaletteArgb.ON_SURFACE_VARIANT)
val Outline = Color(PaletteArgb.OUTLINE)
val OutlineVariant = Color(PaletteArgb.OUTLINE_VARIANT)
val PrimaryContainer = Color(PaletteArgb.PRIMARY_CONTAINER)
val OnPrimaryContainer = Color(PaletteArgb.ON_PRIMARY_CONTAINER)
val InverseSurface = Color(PaletteArgb.INVERSE_SURFACE)
val InverseOnSurface = Color(PaletteArgb.INVERSE_ON_SURFACE)
val Scrim = Color(PaletteArgb.SCRIM)
