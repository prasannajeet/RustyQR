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

private fun monoTypography(mono: FontFamily): Typography {
    val titleLarge =
        TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
    val labelSmall =
        TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
        )
    val labelMedium =
        TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
        )
    val labelLarge =
        TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    return Typography(
        titleLarge = titleLarge,
        labelSmall = labelSmall,
        labelMedium = labelMedium,
        labelLarge = labelLarge,
    )
}

private fun interTypography(inter: FontFamily): Typography {
    val bodySmall =
        TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
        )
    val bodyMedium =
        TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
        )
    val bodyLarge =
        TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
        )
    val titleMedium =
        TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    val titleSmall =
        TextStyle(
            fontFamily = inter,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
        )
    return Typography(
        bodySmall = bodySmall,
        bodyMedium = bodyMedium,
        bodyLarge = bodyLarge,
        titleMedium = titleMedium,
        titleSmall = titleSmall,
    )
}

/**
 * Rusty QR typography scale.
 *
 * Color is intentionally absent from all TextStyle entries — [LocalContentColor] propagates
 * from the theme. Call sites use `.copy(color = ...)` where an explicit override is needed.
 */
@Composable
fun rustyQrTypography(): Typography {
    val monoStyles = monoTypography(jetBrainsMonoFontFamily())
    val interStyles = interTypography(interFontFamily())
    return Typography(
        // App title bar — JetBrains Mono
        titleLarge = monoStyles.titleLarge,
        // Section labels, badges — JetBrains Mono
        labelSmall = monoStyles.labelSmall,
        labelMedium = monoStyles.labelMedium,
        // Button labels — JetBrains Mono bold
        labelLarge = monoStyles.labelLarge,
        // Body text — Inter
        bodySmall = interStyles.bodySmall,
        bodyMedium = interStyles.bodyMedium,
        bodyLarge = interStyles.bodyLarge,
        // Input fields, hint text — Inter
        titleMedium = interStyles.titleMedium,
        titleSmall = interStyles.titleSmall,
    )
}
