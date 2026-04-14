package com.p2.apps.rustyqr.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2.apps.rustyqr.bridge.toImageBitmap
import org.jetbrains.compose.resources.stringResource
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.action_save
import rustyqr.composeapp.generated.resources.action_share
import rustyqr.composeapp.generated.resources.generate_close_cd
import rustyqr.composeapp.generated.resources.generate_qr_content_description

/**
 * Card displaying the generated QR code image with share and save icon buttons.
 *
 * [onClose] fires when the user taps the top-end close button, which animates the screen back to
 * the input form via the existing [GenerateQRCodeScreenIntent.ClearResult] path.
 */
@Composable
fun QrResultCard(
    qrImageBytes: ByteArray,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    onShare: () -> Unit = {},
    onSave: () -> Unit = {},
) {
    val bitmap = remember(qrImageBytes) { qrImageBytes.toImageBitmap() }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        border =
            BorderStroke(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            ),
        elevation = CardDefaults.outlinedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                CloseChip(onClick = onClose)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // White QR image container — functional white background for the scannable code,
            // intentionally not themed (scanner requires high contrast white background).
            Surface(
                shape = MaterialTheme.shapes.small,
                color = Color.White,
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(Res.string.generate_qr_content_description),
                    modifier =
                        Modifier
                            .size(240.dp)
                            .padding(8.dp),
                    contentScale = ContentScale.Fit,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val shareLabel = stringResource(Res.string.action_share)
            val saveLabel = stringResource(Res.string.action_save)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconActionButton(
                    icon = Icons.Outlined.IosShare,
                    contentDescription = shareLabel,
                    label = shareLabel,
                    onClick = onShare,
                )
                IconActionButton(
                    icon = Icons.Outlined.Save,
                    contentDescription = saveLabel,
                    label = saveLabel,
                    onClick = onSave,
                )
            }
        }
    }
}

@Composable
private fun CloseChip(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.minimumInteractiveComponentSize().size(36.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(Res.string.generate_close_cd),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            onClick = onClick,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                ),
        )
    }
}
