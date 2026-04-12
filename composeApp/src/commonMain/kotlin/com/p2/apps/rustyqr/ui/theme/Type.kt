package com.p2.apps.rustyqr.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.inter_bold
import rustyqr.composeapp.generated.resources.inter_regular
import rustyqr.composeapp.generated.resources.inter_semibold
import rustyqr.composeapp.generated.resources.jetbrains_mono_bold
import rustyqr.composeapp.generated.resources.jetbrains_mono_regular

@Composable
fun jetBrainsMonoFontFamily() =
    FontFamily(
        Font(Res.font.jetbrains_mono_regular, weight = FontWeight.Normal),
        Font(Res.font.jetbrains_mono_bold, weight = FontWeight.Bold),
    )

@Composable
fun interFontFamily() =
    FontFamily(
        Font(Res.font.inter_regular, weight = FontWeight.Normal),
        Font(Res.font.inter_semibold, weight = FontWeight.SemiBold),
        Font(Res.font.inter_bold, weight = FontWeight.Bold),
    )

/**
 * Rusty QR typography scale.
 *
 * Color is intentionally absent from all TextStyle entries — [LocalContentColor] propagates
 * from the theme. Call sites use `.copy(color = ...)` where an explicit override is needed.
 */
@Composable
fun rustyQrTypography(): Typography {
    val mono = jetBrainsMonoFontFamily()
    val inter = interFontFamily()
    return Typography(
        // App title bar — JetBrains Mono
        titleLarge =
            TextStyle(
                fontFamily = mono,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            ),
        // Section labels, badges — JetBrains Mono
        labelSmall =
            TextStyle(
                fontFamily = mono,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = mono,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
            ),
        // Button labels — JetBrains Mono bold
        labelLarge =
            TextStyle(
                fontFamily = mono,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            ),
        // Body text — Inter
        bodySmall =
            TextStyle(
                fontFamily = inter,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = inter,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = inter,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
            ),
        // Input fields, hint text — Inter
        titleMedium =
            TextStyle(
                fontFamily = inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = inter,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
            ),
    )
}
