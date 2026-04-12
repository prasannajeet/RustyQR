package com.p2.apps.rustyqr.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2.apps.rustyqr.bridge.CameraPreview
import com.p2.apps.rustyqr.ui.components.AmberButton
import com.p2.apps.rustyqr.ui.components.ScanResultSheet
import com.p2.apps.rustyqr.ui.components.ScannerOverlay
import com.p2.apps.rustyqr.ui.mvi.ScanQRCodeScreenIntent
import com.p2.apps.rustyqr.ui.mvi.ScanScreenState
import com.p2.apps.rustyqr.ui.viewmodels.ScanViewModel
import org.jetbrains.compose.resources.stringResource
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.scan_camera_denied_cd
import rustyqr.composeapp.generated.resources.scan_grant_permission
import rustyqr.composeapp.generated.resources.scan_hint
import rustyqr.composeapp.generated.resources.scan_open_settings
import rustyqr.composeapp.generated.resources.scan_permission_body
import rustyqr.composeapp.generated.resources.scan_permission_title
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
    if (state.isSheetVisible && state.sheetContent != null) {
        ScanResultSheet(
            result = state.sheetContent!!,
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
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.permissionRequested && !state.hasPermission -> {
                PermissionDeniedContent(
                    isPermanentlyDenied = state.isPermanentlyDenied,
                    onGrantPermission = { onIntent(ScanQRCodeScreenIntent.RequestPermission) },
                    onOpenSettings = { onIntent(ScanQRCodeScreenIntent.OpenSettings) },
                )
            }
            state.isCameraActive && state.hasPermission -> {
                // Camera preview fills screen edge-to-edge (no inset padding on this layer)
                CameraPreview(
                    isScanning = state.isScanning,
                    onQrDecoded = { result -> onIntent(ScanQRCodeScreenIntent.FrameDecoded(result)) },
                )

                // UI overlay layer — respects safe drawing insets so overlaid content avoids cutouts
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    // Scrim overlay when sheet is showing
                    if (state.isSheetVisible) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.6f)),
                        )
                    }

                    // Scanner overlay (corner brackets)
                    ScannerOverlay()

                    // Hint text — wrapped in scrim pill for WCAG contrast on camera feed
                    if (!state.isSheetVisible) {
                        Surface(
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .padding(top = 180.dp),
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Text(
                                text = stringResource(Res.string.scan_hint),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontSize = 14.sp,
                                    ),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
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
private fun IdleContent(onStartScanning: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(32.dp),
    ) {
        AmberButton(
            text = stringResource(Res.string.scan_start_scanning),
            onClick = onStartScanning,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun PermissionDeniedContent(
    isPermanentlyDenied: Boolean,
    onGrantPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Camera icon with strike-through
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.CameraAlt,
                        contentDescription = stringResource(Res.string.scan_camera_denied_cd),
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Strike-through line
            Box(
                modifier =
                    Modifier
                        .size(width = 80.dp, height = 2.dp)
                        .background(MaterialTheme.colorScheme.error),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(Res.string.scan_permission_title),
            style =
                MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                ),
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(Res.string.scan_permission_body),
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                ),
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (isPermanentlyDenied) {
            AmberButton(
                text = stringResource(Res.string.scan_open_settings),
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AmberButton(
                text = stringResource(Res.string.scan_grant_permission),
                onClick = onGrantPermission,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
