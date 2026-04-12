package com.p2.apps.rustyqr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.p2.apps.rustyqr.bridge.openUrl
import com.p2.apps.rustyqr.model.QrContentType
import com.p2.apps.rustyqr.model.ScanResult
import com.p2.apps.rustyqr.model.detectContentType
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.action_copy
import rustyqr.composeapp.generated.resources.action_open
import rustyqr.composeapp.generated.resources.scan_again

/**
 * Modal bottom sheet displaying the decoded QR code result.
 *
 * Peek height: ~40% of screen. Expandable to ~70% for long content.
 * Dismiss via swipe-down fires [onDismiss] intent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultSheet(
    result: ScanResult,
    onDismiss: () -> Unit,
    onScanAgain: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val contentType = detectContentType(result.content)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        tonalElevation = 3.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = {
            // Standard drag handle — 2dp radius intentional (small pill per MD3 drag handle spec)
            Surface(
                modifier =
                    Modifier
                        .padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(2.dp),
            ) {
                Spacer(
                    modifier =
                        Modifier
                            .height(4.dp)
                            .padding(horizontal = 16.dp),
                )
            }
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Content type badge
            ContentTypeBadge(
                contentType = contentType,
                modifier = Modifier.align(Alignment.Start),
            )

            // Decoded content — selectable, scrollable for long content
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = result.content,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    maxLines = 8,
                )
            }

            // Action row
            if (contentType == QrContentType.URL) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AmberButton(
                        text = stringResource(Res.string.action_open),
                        onClick = { openUrl(result.content) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlineButton(
                        text = stringResource(Res.string.action_copy),
                        onClick = {
                            clipboard.setText(AnnotatedString(result.content))
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                AmberButton(
                    text = stringResource(Res.string.action_copy),
                    onClick = {
                        clipboard.setText(AnnotatedString(result.content))
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                )
            }

            // Scan Again
            TextButton(
                onClick = {
                    scope.launch {
                        sheetState.hide()
                        onScanAgain()
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = stringResource(Res.string.scan_again),
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
        }
    }
}
