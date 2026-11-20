package com.p2.apps.rustyqr.bridge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.p2.apps.rustyqr.model.CameraFailure
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
 * @param onCameraReady Fired at most once per entry into composition, on the main thread,
 *                      when the first frame is delivered. Drives the "Starting camera…" loader overlay so the
 *                      user never sees a black screen during AVCaptureSession / CameraX
 *                      warm-up.
 * @param onCameraError Fired at most once per entry into composition, on the main thread, with the first failure:
 *                      - [CameraFailure.NoCamera]: no usable back camera on the device (the iOS simulator, or an
 *                        Android device without a back camera), found before anything is started.
 *                      - [CameraFailure.CouldNotStart]: the session could not be set up (camera permission missing,
 *                        input or output rejected, CameraX failed to initialise or bind), or it failed after
 *                        starting: a critical CameraX camera-state error (camera disabled by policy, fatal camera
 *                        error, stream configuration failure, do-not-disturb mode) on Android, or an
 *                        `AVCaptureSessionRuntimeError` on iOS.
 *                      Not reported: errors CameraX retries itself (recoverable camera-state errors) and iOS session
 *                      interruptions, which the system resumes. [onCameraReady] does not fire for a session that
 *                      failed before its first frame, and frames stop being decoded once a failure is reported.
 * @param modifier Applied to the native camera view.
 *
 * Contract every actual must satisfy:
 * - It always reads the latest [isScanning] and invokes the latest [onQrDecoded], [onCameraReady] and
 *   [onCameraError], never the ones from the first composition, because the camera outlives recompositions.
 * - It never calls back after the preview has left composition.
 * - [onCameraReady] fires at most once per entry into composition.
 * - [onCameraError] fires at most once per entry into composition and leaves nothing bound or started by the
 *   failed setup. A failure after start also unbinds on Android; on iOS the session stops when the caller removes
 *   the preview.
 */
@Composable
expect fun CameraPreview(
    isScanning: Boolean,
    onQrDecoded: (ScanResult) -> Unit,
    onCameraReady: () -> Unit,
    onCameraError: (CameraFailure) -> Unit,
    modifier: Modifier = Modifier,
)
