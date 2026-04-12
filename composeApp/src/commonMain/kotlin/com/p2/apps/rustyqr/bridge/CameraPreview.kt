package com.p2.apps.rustyqr.bridge

import androidx.compose.runtime.Composable
import com.p2.apps.rustyqr.model.ScanResult

/**
 * Camera preview composable.
 *
 * Android: CameraX [PreviewView] + [ImageAnalysis] via [AndroidView].
 * iOS: Placeholder [Box] for Phase 7 (AVFoundation).
 *
 * @param isScanning When true, frames are decoded. When false, analysis is paused
 *                   (camera session stays alive — no black flash).
 * @param onQrDecoded Callback invoked on the main thread when a QR code is decoded.
 */
@Composable
expect fun CameraPreview(
    isScanning: Boolean,
    onQrDecoded: (ScanResult) -> Unit,
)
