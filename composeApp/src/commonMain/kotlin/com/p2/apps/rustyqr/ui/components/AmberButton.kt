package com.p2.apps.rustyqr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val LOADING_INDICATOR_SIZE_DP = 20
private const val LOADING_INDICATOR_STROKE_DP = 2
private const val LOADING_SPACER_DP = 12

/**
 * Primary filled amber button — amber background, dark text, JetBrains Mono bold label.
 *
 * @param loading When true, the button is disabled and a trailing [CircularProgressIndicator] is
 *                shown next to [text]. The caller controls the label shown while loading (e.g.
 *                `"Generating…"`).
 */
@Composable
fun AmberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val effectiveEnabled = enabled && !loading
    Button(
        onClick = onClick,
        enabled = effectiveEnabled,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                style =
                    MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (effectiveEnabled) onPrimary else onPrimary.copy(alpha = 0.6f),
                    ),
            )
            if (loading) {
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier.size(LOADING_SPACER_DP.dp),
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(LOADING_INDICATOR_SIZE_DP.dp),
                    color = onPrimary.copy(alpha = 0.8f),
                    strokeWidth = LOADING_INDICATOR_STROKE_DP.dp,
                )
            }
        }
    }
}
