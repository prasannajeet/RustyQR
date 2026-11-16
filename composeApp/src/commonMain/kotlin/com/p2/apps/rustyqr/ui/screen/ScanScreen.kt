package com.p2.apps.rustyqr.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2.apps.rustyqr.bridge.CameraPreview
import com.p2.apps.rustyqr.ui.components.AmberButton
import com.p2.apps.rustyqr.ui.components.CameraFailureContent
import com.p2.apps.rustyqr.ui.components.PermissionDeniedContent
import com.p2.apps.rustyqr.ui.components.ScanResultSheet
import com.p2.apps.rustyqr.ui.components.ScannerOverlay
import com.p2.apps.rustyqr.ui.mvi.ScanQRCodeScreenIntent
import com.p2.apps.rustyqr.ui.mvi.ScanScreenState
import com.p2.apps.rustyqr.ui.viewmodels.ScanViewModel
import org.jetbrains.compose.resources.stringResource
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.scan_camera_starting
import rustyqr.composeapp.generated.resources.scan_hint
import rustyqr.composeapp.generated.resources.scan_start_scanning

/**
 * Scan screen — camera preview with QR scanning, scanner overlay, and result bottom sheet.
 *
 * Opens in an idle state — the camera stays cold until the user taps "Start Scanning".
 * Permission is checked lazily at that point; there is no eager permission poll on resume.
 *
 * Wires [ScanViewModel] to [ScanContent].
 */
@Composable
fun ScanScreen(viewModel: ScanViewModel = viewModel { ScanViewModel() }) {
    val state by viewModel.state.collectAsState()

    ScanContent(
        state = state,
        onIntent = viewModel::onIntent,
    )

    // Show result sheet when QR decoded
    val sheetContent = state.sheetContent
    if (state.isSheetVisible && sheetContent != null) {
        ScanResultSheet(
            result = sheetContent,
            onDismiss = { viewModel.onIntent(ScanQRCodeScreenIntent.DismissSheet) },
            onScanAgain = { viewModel.onIntent(ScanQRCodeScreenIntent.ResumeScanning) },
        )
    }
}

@Composable
private fun ScanContent(
    state: ScanScreenState,
    onIntent: (ScanQRCodeScreenIntent) -> Unit,
) {
    val contentModifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    Box(
        modifier = contentModifier,
    ) {
        when {
            state.permissionRequested && !state.hasPermission -> {
                PermissionDeniedContent(
                    isPermanentlyDenied = state.isPermanentlyDenied,
                    onGrantPermission = { onIntent(ScanQRCodeScreenIntent.RequestPermission) },
                    onOpenSettings = { onIntent(ScanQRCodeScreenIntent.OpenSettings) },
                )
            }

            state.cameraFailure != null -> {
                CameraFailureContent(
                    failure = state.cameraFailure,
                    onTryAgain = { onIntent(ScanQRCodeScreenIntent.RetryCamera) },
                )
            }

            state.isCameraActive && state.hasPermission -> {
                CameraScanLayer(state = state, onIntent = onIntent)
            }

            else -> {
                IdleContent(
                    onStartScanning = { onIntent(ScanQRCodeScreenIntent.StartScanning) },
                )
            }
        }
    }
}

@Composable
private fun CameraScanLayer(
    state: ScanScreenState,
    onIntent: (ScanQRCodeScreenIntent) -> Unit,
) {
    // Camera preview fills screen edge-to-edge (no inset padding on this layer)
    CameraPreview(
        isScanning = state.isScanning,
        onQrDecoded = { result -> onIntent(ScanQRCodeScreenIntent.FrameDecoded(result)) },
        onCameraReady = { onIntent(ScanQRCodeScreenIntent.CameraReady) },
        onCameraError = { failure -> onIntent(ScanQRCodeScreenIntent.CameraFailed(failure)) },
        modifier = Modifier.fillMaxSize(),
    )

    // Warm-up loader overlay — covers the black-while-warming-up frame so the user
    // sees a deliberate loading state instead of a dead preview. Cleared on the first
    // camera frame via ScanQRCodeScreenIntent.CameraReady.
    if (state.isCameraWarmingUp) {
        CameraWarmingUpOverlay()
    }

    // UI overlay layer — respects safe drawing insets so overlaid content avoids cutouts
    val overlayModifier =
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    Box(
        modifier = overlayModifier,
    ) {
        // Scrim overlay when sheet is showing
        if (state.isSheetVisible) {
            val scrimModifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
            Box(
                modifier = scrimModifier,
            )
        }

        // Scanner overlay (corner brackets)
        ScannerOverlay()

        // Hint text — wrapped in scrim pill for WCAG contrast on camera feed
        if (!state.isSheetVisible) {
            ScanHintPill()
        }
    }
}

@Composable
private fun BoxScope.ScanHintPill() {
    val pillModifier =
        Modifier
            .align(Alignment.Center)
            .padding(top = 180.dp)
    Surface(
        modifier = pillModifier,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.large,
    ) {
        val hintStyle =
            MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
            )
        Text(
            text = stringResource(Res.string.scan_hint),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = hintStyle,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CameraWarmingUpOverlay() {
    val warmUpModifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    Box(
        modifier = warmUpModifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            val warmUpTextStyle =
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                )
            Text(
                text = stringResource(Res.string.scan_camera_starting),
                style = warmUpTextStyle,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun IdleContent(onStartScanning: () -> Unit) {
    val idleModifier =
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp)
    Box(
        modifier = idleModifier,
    ) {
        AmberButton(
            text = stringResource(Res.string.scan_start_scanning),
            onClick = onStartScanning,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
