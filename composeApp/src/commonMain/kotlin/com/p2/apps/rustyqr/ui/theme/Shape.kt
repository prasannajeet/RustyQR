package com.p2.apps.rustyqr.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Rusty QR shape scale, wired into [MaterialTheme] via [RustyQrTheme].
 *
 * Feature code uses [MaterialTheme.shapes.*] — do not import these values directly outside
 * [ui/theme/].
 */
val RustyShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
