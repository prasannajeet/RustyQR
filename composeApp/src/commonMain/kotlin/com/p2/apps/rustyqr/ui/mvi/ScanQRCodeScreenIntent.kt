package com.p2.apps.rustyqr.ui.mvi

import com.p2.apps.rustyqr.model.CameraFailure
import com.p2.apps.rustyqr.model.ScanResult

/**
 * User intents for the Scan screen.
 */
sealed interface ScanQRCodeScreenIntent {
    /** Camera frame was successfully decoded. First decode locks the scan gate. */
    data class FrameDecoded(
        val result: ScanResult,
    ) : ScanQRCodeScreenIntent

    /** User dismissed the result sheet (swipe or "Scan Again" button). */
    data object DismissSheet : ScanQRCodeScreenIntent

    /** User tapped "Scan Again" — alias for [DismissSheet], both unlock the gate. */
    data object ResumeScanning : ScanQRCodeScreenIntent

    /** Permission check result received. */
    data class PermissionResult(
        val granted: Boolean,
    ) : ScanQRCodeScreenIntent

    /** User tapped "Grant Permission". */
    data object RequestPermission : ScanQRCodeScreenIntent

    /** User tapped "Open Settings" (permission permanently denied). */
    data object OpenSettings : ScanQRCodeScreenIntent

    /** User tapped "Start Scanning" on the idle screen — lazily checks permission and activates camera. */
    data object StartScanning : ScanQRCodeScreenIntent

    /** The camera is unusable: none on the device, or it failed to start or died mid-session. Shows the error. */
    data class CameraFailed(
        val failure: CameraFailure,
    ) : ScanQRCodeScreenIntent

    /** User tapped "Try Again" on a retryable camera error — clears it and starts the camera again. */
    data object RetryCamera : ScanQRCodeScreenIntent

    /** First camera frame has been delivered — clears the warm-up loader overlay. */
    data object CameraReady : ScanQRCodeScreenIntent

    /** A camera preview session began: show the warm-up loader again until it delivers its first frame. */
    data object PreviewStarted : ScanQRCodeScreenIntent

    /** The app resumed: the ViewModel re-checks a denied permission and restarts a camera blocked by device policy. */
    data object ScreenResumed : ScanQRCodeScreenIntent
}
