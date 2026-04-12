package com.p2.apps.rustyqr.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Primary filled amber button — amber background, dark text, JetBrains Mono bold label.
 */
@Composable
fun AmberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .fillMaxWidth()
                .height(52.dp),
        shape = MaterialTheme.shapes.medium,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = primary,
                contentColor = onPrimary,
                disabledContainerColor = primary.copy(alpha = 0.4f),
                disabledContentColor = onPrimary.copy(alpha = 0.4f),
            ),
    ) {
        Text(
            text = text,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) onPrimary else onPrimary.copy(alpha = 0.6f),
                ),
        )
    }
}
