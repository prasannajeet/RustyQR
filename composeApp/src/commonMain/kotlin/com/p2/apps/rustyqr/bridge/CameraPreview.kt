package com.p2.apps.rustyqr.bridge

import androidx.compose.runtime.Composable
import com.p2.apps.rustyqr.model.ScanResult

/**
 * Camera preview composable.
 *
 * Android: CameraX [PreviewView] + [ImageAnalysis] via [AndroidView].
 * iOS: AVFoundation [AVCaptureSession] hosted via [UIKitView].
 *
 * @param isScanning When true, frames are decoded. When false, analysis is paused
 *                   (camera session stays alive — no black flash).
 * @param onQrDecoded Callback invoked on the main thread when a QR code is decoded.
 * @param onCameraReady Fired exactly once on the main thread when the first frame is
 *                      delivered. Drives the "Starting camera…" loader overlay so the
 *                      user never sees a black screen during AVCaptureSession / CameraX
 *                      warm-up.
 *
 * Contract every actual must satisfy:
 * - It always invokes the latest [isScanning], [onQrDecoded] and [onCameraReady], never the ones from the
 *   first composition, because the camera outlives recompositions.
 * - It never calls back after the preview has left composition.
 * - [onCameraReady] fires at most once per entry into composition.
 */
@Composable
expect fun CameraPreview(
    isScanning: Boolean,
    onQrDecoded: (ScanResult) -> Unit,
    onCameraReady: () -> Unit,
)
