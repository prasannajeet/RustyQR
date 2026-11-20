package com.p2.apps.rustyqr.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.p2.apps.rustyqr.bridge.CameraPermissions
import com.p2.apps.rustyqr.bridge.PlatformCameraPermissions
import com.p2.apps.rustyqr.bridge.triggerHaptic
import com.p2.apps.rustyqr.model.CameraFailure
import com.p2.apps.rustyqr.ui.mvi.ScanQRCodeScreenIntent
import com.p2.apps.rustyqr.ui.mvi.ScanScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Processes [ScanQRCodeScreenIntent]s and manages [ScanScreenState].
 *
 * All permission queries and requests go through [permissions]; tests substitute it.
 *
 * The [scanGate] Boolean is read and written only on the main thread.
 * State mutations always happen on the main thread via [kotlinx.coroutines.Dispatchers.Main].
 *
 * Gate lifecycle:
 *   - Starts unlocked (false) — camera is analyzing
 *   - Locks (true) on first [ScanQRCodeScreenIntent.FrameDecoded] (first-write-wins)
 *   - Unlocks on [ScanQRCodeScreenIntent.DismissSheet] / [ScanQRCodeScreenIntent.ResumeScanning]
 *   - Unlocks on [ScanQRCodeScreenIntent.CameraFailed]: the session is gone, so no frame can arrive, and a gate left
 *     locked would swallow the first decode of the retried session
 */
class ScanViewModel(
    private val permissions: CameraPermissions = PlatformCameraPermissions,
) : ViewModel() {
    val state: StateFlow<ScanScreenState>
        field = MutableStateFlow(ScanScreenState())

    /**
     * First-write-wins gate. Read and written only on the main thread.
     * true = gate locked (sheet showing), false = gate open (analyzing frames).
     */
    var scanGate: Boolean = false
        private set

    fun onIntent(intent: ScanQRCodeScreenIntent) {
        when (intent) {
            is ScanQRCodeScreenIntent.FrameDecoded -> handleFrameDecoded(intent)
            is ScanQRCodeScreenIntent.DismissSheet -> handleDismissSheet()
            is ScanQRCodeScreenIntent.ResumeScanning -> handleDismissSheet()
            is ScanQRCodeScreenIntent.PermissionResult -> handlePermissionResult(intent.granted)
            is ScanQRCodeScreenIntent.RequestPermission -> handleRequestPermission()
            is ScanQRCodeScreenIntent.OpenSettings -> permissions.openSettings()
            is ScanQRCodeScreenIntent.StartScanning -> handleStartScanning()
            is ScanQRCodeScreenIntent.CameraReady -> handleCameraReady()
            is ScanQRCodeScreenIntent.PreviewStarted -> handlePreviewStarted()
            is ScanQRCodeScreenIntent.ScreenResumed -> handleScreenResumed()
            is ScanQRCodeScreenIntent.CameraFailed -> handleCameraFailed(intent.failure)
            is ScanQRCodeScreenIntent.RetryCamera -> handleRetryCamera()
        }
    }

    private fun handleCameraReady() {
        state.update { it.copy(isCameraWarmingUp = false) }
    }

    /**
     * A new preview session began, for example when the user returns to Scan with the camera still active. The
     * session has not delivered a frame yet, so the warm-up loader covers the black preview until it does.
     */
    private fun handlePreviewStarted() {
        if (state.value.isCameraActive) {
            state.update { it.copy(isCameraWarmingUp = true) }
        }
    }

    /**
     * The app came back to the foreground. Two screens wait for the user to change something outside the app, and
     * neither gets a callback when they do:
     * - A camera blocked by device policy: clear the failure and start the camera again. If the device still blocks
     *   it, CameraX reports the failure again.
     * - The permission-denied screen: the user may have granted the permission in Settings, so ask the system again.
     */
    private fun handleScreenResumed() {
        val current = state.value
        when {
            current.cameraFailure == CameraFailure.BlockedByDevice -> {
                state.update { it.copy(cameraFailure = null).withCameraActive(true) }
            }

            current.permissionRequested && !current.hasPermission && permissions.isGranted() -> {
                handlePermissionResult(granted = true)
            }
        }
    }

    private fun handleCameraFailed(failure: CameraFailure) {
        // Dropping isCameraActive removes the preview from composition, which releases the camera. The session is
        // over, so no frame can arrive; unlock the gate so a decode that locked it just before the failure cannot
        // block the first decode of a retried session.
        scanGate = false
        state.update {
            it.copy(
                isScanning = false,
                isCameraActive = false,
                isCameraWarmingUp = false,
                cameraFailure = failure,
            )
        }
    }

    /**
     * One tap retries: clears the failure and starts the camera again. The preview already left composition, so it
     * comes back with a fresh dispatcher and session. Ignored for failures retrying cannot fix (no camera). Permission
     * was granted when the camera first started; if it was revoked since, the new session reports a failure again.
     */
    private fun handleRetryCamera() {
        if (state.value.cameraFailure?.isRetryable != true) return
        state.update { it.copy(cameraFailure = null).withCameraActive(true) }
    }

    private fun handleFrameDecoded(intent: ScanQRCodeScreenIntent.FrameDecoded) {
        // First-write-wins: only the first FrameDecoded processed (gate starts false)
        if (scanGate) return
        scanGate = true

        triggerHaptic()
        state.update {
            it.copy(
                isScanning = false,
                sheetContent = intent.result,
                isSheetVisible = true,
            )
        }
    }

    private fun handleDismissSheet() {
        scanGate = false
        state.update {
            it.copy(
                isScanning = false,
                isCameraActive = false,
                isCameraWarmingUp = false,
                sheetContent = null,
                isSheetVisible = false,
            )
        }
    }

    private fun handlePermissionResult(granted: Boolean) {
        val permanentlyDenied = if (!granted) permissions.isPermanentlyDenied() else false
        state.update {
            it
                .copy(
                    hasPermission = granted,
                    permissionRequested = true,
                    isPermanentlyDenied = permanentlyDenied,
                ).withCameraActive(granted)
        }
    }

    private fun handleRequestPermission() {
        permissions.request { granted ->
            onIntent(ScanQRCodeScreenIntent.PermissionResult(granted))
        }
    }

    private fun handleStartScanning() {
        if (permissions.isGranted()) {
            state.update {
                it
                    .copy(
                        hasPermission = true,
                        permissionRequested = true,
                        isPermanentlyDenied = false,
                    ).withCameraActive(true)
            }
        } else {
            permissions.request { granted ->
                onIntent(ScanQRCodeScreenIntent.PermissionResult(granted))
            }
        }
    }
}

/** The camera on and scanning with the warm-up loader showing, or all three off. */
private fun ScanScreenState.withCameraActive(active: Boolean): ScanScreenState =
    copy(isCameraActive = active, isScanning = active, isCameraWarmingUp = active)
