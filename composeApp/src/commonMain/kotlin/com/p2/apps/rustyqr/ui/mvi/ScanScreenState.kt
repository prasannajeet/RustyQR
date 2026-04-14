package com.p2.apps.rustyqr.ui.mvi

import com.p2.apps.rustyqr.model.ScanResult

/**
 * Immutable state for the Scan screen.
 *
 * The bottom sheet is driven entirely by state — no [SharedFlow] navigation events.
 * [isSheetVisible] + [sheetContent] together control the result sheet.
 */
data class ScanScreenState(
    /** Whether the camera analyzer is actively processing frames. */
    val isScanning: Boolean = false,
    /** Whether the camera preview is active (user has tapped "Start Scanning" and been granted permission). */
    val isCameraActive: Boolean = false,
    /**
     * True between the moment the user taps "Start Scanning" (with permission granted) and the
     * first camera frame actually being delivered. Drives the "Starting camera…" loader overlay
     * so the screen never goes visibly black while AVCaptureSession / CameraX warm up.
     */
    val isCameraWarmingUp: Boolean = false,
    /** Decoded QR content to display in the bottom sheet. Null when sheet is hidden. */
    val sheetContent: ScanResult? = null,
    /** Whether the result bottom sheet is currently shown. */
    val isSheetVisible: Boolean = false,
    /** Whether camera permission has been granted. */
    val hasPermission: Boolean = false,
    /** Whether the permission request has been made at least once. */
    val permissionRequested: Boolean = false,
    /** Whether camera permission is permanently denied ("Don't ask again"). */
    val isPermanentlyDenied: Boolean = false,
)
