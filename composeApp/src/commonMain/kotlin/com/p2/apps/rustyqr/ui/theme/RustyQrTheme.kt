package com.p2.apps.rustyqr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RustyDarkColorScheme =
    darkColorScheme(
        primary = Primary,
        onPrimary = OnPrimary,
        primaryContainer = PrimaryContainer,
        onPrimaryContainer = OnPrimaryContainer,
        background = Background,
        onBackground = OnBackground,
        surface = Surface,
        onSurface = OnBackground,
        surfaceVariant = SurfaceVariant,
        onSurfaceVariant = OnSurfaceVariant,
        surfaceContainerLowest = SurfaceContainerLowest,
        surfaceContainerLow = SurfaceContainerLow,
        surfaceContainer = SurfaceContainer,
        surfaceContainerHigh = SurfaceContainerHigh,
        surfaceContainerHighest = SurfaceContainerHighest,
        outline = Outline,
        outlineVariant = OutlineVariant,
        inverseSurface = InverseSurface,
        inverseOnSurface = InverseOnSurface,
        scrim = Scrim,
        error = Error,
        onError = OnBackground,
    )

/**
 * Rusty QR custom dark theme wrapper.
 *
 * Uses [darkColorScheme] with Rust-branded amber accent ([Primary] = #F5A623).
 * No dynamic color — consistent brand across devices.
 * Secondary text uses [onSurfaceVariant]; high-emphasis text uses [onBackground] via [onSurface].
 */
@Composable
fun RustyQrTheme(content: @Composable () -> Unit) {
    val typography = rustyQrTypography()
    MaterialTheme(
        colorScheme = RustyDarkColorScheme,
        shapes = RustyShapes,
        typography = typography,
        content = content,
    )
}
