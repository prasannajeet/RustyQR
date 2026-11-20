package com.p2.apps.rustyqr.ui.viewmodels

import com.p2.apps.rustyqr.model.CameraFailure
import com.p2.apps.rustyqr.model.ScanResult
import com.p2.apps.rustyqr.ui.mvi.ScanQRCodeScreenIntent
import com.p2.apps.rustyqr.ui.mvi.ScanScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanViewModelTest {
    private val viewModel = ScanViewModel()

    private fun startCamera() {
        // Same state StartScanning produces once permission is granted, without touching the permission bridge.
        viewModel.onIntent(ScanQRCodeScreenIntent.PermissionResult(granted = true))
    }

    @Test
    fun cameraFailedClearsWarmUpAndRecordsTheFailure() {
        startCamera()
        assertEquals(true, viewModel.state.value.isCameraWarmingUp)

        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.NoCamera))

        val state = viewModel.state.value
        assertEquals(CameraFailure.NoCamera, state.cameraFailure)
        assertFalse(state.isCameraWarmingUp)
        assertFalse(state.isCameraActive)
        assertFalse(state.isScanning)
    }

    @Test
    fun cameraFailedKeepsPermissionSoTheErrorIsNotShownAsDenied() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.CouldNotStart))
        assertEquals(true, viewModel.state.value.hasPermission)
        assertEquals(CameraFailure.CouldNotStart, viewModel.state.value.cameraFailure)
    }

    @Test
    fun retryClearsTheFailureAndStartsTheCameraAgain() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.CouldNotStart))

        viewModel.onIntent(ScanQRCodeScreenIntent.RetryCamera)

        val state = viewModel.state.value
        assertNull(state.cameraFailure)
        assertTrue(state.isCameraActive)
        assertTrue(state.isCameraWarmingUp)
        assertTrue(state.isScanning)
    }

    @Test
    fun retryIsIgnoredWhenThereIsNoCamera() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.NoCamera))
        val failed = viewModel.state.value

        viewModel.onIntent(ScanQRCodeScreenIntent.RetryCamera)

        assertEquals(failed, viewModel.state.value)
    }

    @Test
    fun retryWithoutAFailureDoesNothing() {
        val before = viewModel.state.value
        viewModel.onIntent(ScanQRCodeScreenIntent.RetryCamera)
        assertEquals(before, viewModel.state.value)
    }

    @Test
    fun staleCameraReadyAfterFailureKeepsTheFailureScreen() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.CouldNotStart))

        viewModel.onIntent(ScanQRCodeScreenIntent.CameraReady)

        val state = viewModel.state.value
        assertEquals(CameraFailure.CouldNotStart, state.cameraFailure)
        assertFalse(state.isCameraActive)
    }

    @Test
    fun failureUnlocksTheScanGate() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("a")))
        assertTrue(viewModel.scanGate)

        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.CouldNotStart))

        assertFalse(viewModel.scanGate)
    }

    @Test
    fun failureWhileResultSheetIsVisibleSurvivesDismissingTheSheet() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("a")))
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.CouldNotStart))

        viewModel.onIntent(ScanQRCodeScreenIntent.DismissSheet)

        val state = viewModel.state.value
        assertEquals(CameraFailure.CouldNotStart, state.cameraFailure)
        assertFalse(state.isSheetVisible)
    }

    @Test
    fun initialStateHasNoFailure() {
        assertEquals(ScanScreenState(), viewModel.state.value)
    }
}
