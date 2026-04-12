package com.p2.apps.rustyqr.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.p2.apps.rustyqr.model.ScanResult

/**
 * iOS actual — placeholder for Phase 7 (AVFoundation implementation).
 */
@Composable
actual fun CameraPreview(
    isScanning: Boolean,
    onQrDecoded: (ScanResult) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    )
}
