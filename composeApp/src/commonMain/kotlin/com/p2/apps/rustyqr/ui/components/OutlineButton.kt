package com.p2.apps.rustyqr.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Secondary outline button — amber border, amber text.
 */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val primary = MaterialTheme.colorScheme.primary
    val outlinedButtonModifier =
        modifier
            .fillMaxWidth()
            .height(52.dp)
    val outlinedButtonBorder =
        BorderStroke(
            width = 1.5.dp,
            color = if (enabled) primary else primary.copy(alpha = 0.4f),
        )
    val outlineButtonColors =
        ButtonDefaults.outlinedButtonColors(
            contentColor = primary,
            containerColor = Color.Transparent,
            disabledContentColor = primary.copy(alpha = 0.4f),
        )
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = outlinedButtonModifier,
        shape = MaterialTheme.shapes.medium,
        border = outlinedButtonBorder,
        colors = outlineButtonColors,
    ) {
        val textStyle =
            MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                color = if (enabled) primary else primary.copy(alpha = 0.4f),
            )
        Text(
            text = text,
            style = textStyle,
        )
    }
}
