package com.p2.apps.rustyqr.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2.apps.rustyqr.model.QrContentType
import org.jetbrains.compose.resources.stringResource

/**
 * Small pill badge displaying "URL" or "TEXT" in JetBrains Mono.
 *
 * Background: [MaterialTheme.colorScheme.primary] at 15% opacity, border at 30% opacity.
 */
@Composable
fun ContentTypeBadge(
    contentType: QrContentType,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = primary.copy(alpha = 0.15f),
        border = BorderStroke(width = 1.dp, color = primary.copy(alpha = 0.3f)),
    ) {
        Text(
            text = stringResource(contentType.labelRes),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = primary,
                ),
        )
    }
}
