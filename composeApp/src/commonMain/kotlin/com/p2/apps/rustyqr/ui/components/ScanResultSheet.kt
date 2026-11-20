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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.p2.apps.rustyqr.bridge.openUrl
import com.p2.apps.rustyqr.bridge.plainTextClipEntry
import com.p2.apps.rustyqr.model.QrContentType
import com.p2.apps.rustyqr.model.ScanResult
import com.p2.apps.rustyqr.model.detectContentType
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.action_copy
import rustyqr.composeapp.generated.resources.action_open
import rustyqr.composeapp.generated.resources.scan_again
import rustyqr.composeapp.generated.resources.scan_clip_label

/**
 * Modal bottom sheet displaying the decoded QR code result.
 *
 * Content taller than half the container opens partially expanded at half the container height and expands to
 * the full content height; shorter content is shown in full. Swiping down dismisses and fires [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultSheet(
    result: ScanResult,
    onDismiss: () -> Unit,
    onScanAgain: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val contentType = detectContentType(result.content)
    val clipLabel = stringResource(Res.string.scan_clip_label)

    val copyAndDismiss: () -> Unit = {
        scope.launch {
            clipboard.setClipEntry(plainTextClipEntry(clipLabel, result.content))
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        tonalElevation = 3.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { ScanResultDragHandle() },
    ) {
        val columnModifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        Column(
            modifier = columnModifier,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Content type badge
            ContentTypeBadge(
                contentType = contentType,
                modifier = Modifier.align(Alignment.Start),
            )

            ScanResultContent(content = result.content)

            ScanResultActions(
                content = result.content,
                isUrl = contentType == QrContentType.URL,
                onCopy = copyAndDismiss,
            )

            // Scan Again
            ScanAgainButton(
                onClick = {
                    scope.launch {
                        sheetState.hide()
                        onScanAgain()
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

// Standard drag handle — 2dp radius intentional (small pill per MD3 drag handle spec)
@Composable
private fun ScanResultDragHandle() {
    val surfaceModifier =
        Modifier
            .padding(vertical = 8.dp)
    Surface(
        modifier = surfaceModifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(2.dp),
    ) {
        val spacerModifier =
            Modifier
                .height(4.dp)
                .padding(horizontal = 16.dp)
        Spacer(
            modifier = spacerModifier,
        )
    }
}

// Decoded content — selectable, scrollable for long content
@Composable
private fun ScanResultContent(content: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        val textModifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        val textStyle =
            MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
            )
        Text(
            text = content,
            modifier = textModifier,
            style = textStyle,
            maxLines = 8,
        )
    }
}

/** Open and copy actions for URLs; a single copy action otherwise. */
@Composable
private fun ScanResultActions(
    content: String,
    isUrl: Boolean,
    onCopy: () -> Unit,
) {
    if (isUrl) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AmberButton(
                text = stringResource(Res.string.action_open),
                onClick = { openUrl(content) },
                modifier = Modifier.weight(1f),
            )
            OutlineButton(
                text = stringResource(Res.string.action_copy),
                onClick = onCopy,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        AmberButton(
            text = stringResource(Res.string.action_copy),
            onClick = onCopy,
        )
    }
}

@Composable
private fun ScanAgainButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        val scanAgainStyle =
            MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Text(
            text = stringResource(Res.string.scan_again),
            style = scanAgainStyle,
        )
    }
}
