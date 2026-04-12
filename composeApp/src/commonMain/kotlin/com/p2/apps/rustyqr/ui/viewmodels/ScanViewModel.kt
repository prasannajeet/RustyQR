package com.p2.apps.rustyqr.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.p2.apps.rustyqr.bridge.isCameraPermissionGranted
import com.p2.apps.rustyqr.bridge.isCameraPermissionPermanentlyDenied
import com.p2.apps.rustyqr.bridge.openAppSettings
import com.p2.apps.rustyqr.bridge.requestCameraPermission
import com.p2.apps.rustyqr.bridge.triggerHaptic
import com.p2.apps.rustyqr.ui.mvi.ScanQRCodeScreenIntent
import com.p2.apps.rustyqr.ui.mvi.ScanScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Processes [ScanQRCodeScreenIntent]s and manages [ScanScreenState].
 *
 * The [scanGate] [@Volatile] Boolean is read directly on the camera thread (fast path).
 * State mutations always happen on the main thread via [kotlinx.coroutines.Dispatchers.Main].
 *
 * Gate lifecycle:
 *   - Starts unlocked (false) — camera is analyzing
 *   - Locks (true) on first [ScanQRCodeScreenIntent.FrameDecoded] (first-write-wins)
 *   - Unlocks on [ScanQRCodeScreenIntent.DismissSheet] / [ScanQRCodeScreenIntent.ResumeScanning]
 */
class ScanViewModel : ViewModel() {
    val state: StateFlow<ScanScreenState>
        field = MutableStateFlow(ScanScreenState())

    /**
     * First-write-wins gate. Written on main thread, read on camera thread.
     * true = gate locked (sheet showing), false = gate open (analyzing frames).
     *
     * Note: The camera analyzer uses [ScanScreenState.isScanning] which is a
     * StateFlow value — the gate here is a fast-path duplicate that avoids
     * StateFlow overhead on the hot camera path.
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
            is ScanQRCodeScreenIntent.OpenSettings -> openAppSettings()
            is ScanQRCodeScreenIntent.StartScanning -> handleStartScanning()
        }
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
                sheetContent = null,
                isSheetVisible = false,
            )
        }
    }

    private fun handlePermissionResult(granted: Boolean) {
        val permanentlyDenied = if (!granted) isCameraPermissionPermanentlyDenied() else false
        state.update {
            it.copy(
                hasPermission = granted,
                permissionRequested = true,
                isCameraActive = granted,
                isScanning = granted,
                isPermanentlyDenied = permanentlyDenied,
            )
        }
    }

    private fun handleRequestPermission() {
        requestCameraPermission { granted ->
            onIntent(ScanQRCodeScreenIntent.PermissionResult(granted))
        }
    }

    private fun handleStartScanning() {
        if (isCameraPermissionGranted()) {
            state.update {
                it.copy(
                    isCameraActive = true,
                    hasPermission = true,
                    permissionRequested = true,
                    isScanning = true,
                    isPermanentlyDenied = false,
                )
            }
        } else {
            requestCameraPermission { granted ->
                onIntent(ScanQRCodeScreenIntent.PermissionResult(granted))
            }
        }
    }
}
