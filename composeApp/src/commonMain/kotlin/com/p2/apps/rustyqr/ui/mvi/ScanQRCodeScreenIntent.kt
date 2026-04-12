package com.p2.apps.rustyqr.ui.mvi

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
}
