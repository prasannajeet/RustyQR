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
    private var permissionGranted = false
    private val viewModel = ScanViewModel(isPermissionGranted = { permissionGranted })

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
    fun cameraReadyClearsTheWarmUpAndKeepsTheCameraRunning() {
        startCamera()

        viewModel.onIntent(ScanQRCodeScreenIntent.CameraReady)

        val state = viewModel.state.value
        assertFalse(state.isCameraWarmingUp)
        assertTrue(state.isCameraActive)
        assertTrue(state.isScanning)
    }

    @Test
    fun secondFrameDecodedIsIgnoredAndTheFirstResultWins() {
        startCamera()

        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("first")))
        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("second")))

        val state = viewModel.state.value
        assertEquals(ScanResult("first"), state.sheetContent)
        assertTrue(state.isSheetVisible)
        assertTrue(viewModel.scanGate)
    }

    @Test
    fun decodeLocksTheGateAndStopsScanning() {
        startCamera()

        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("a")))

        assertTrue(viewModel.scanGate)
        assertFalse(viewModel.state.value.isScanning)
    }

    @Test
    fun dismissSheetUnlocksTheGateStopsTheCameraAndHidesTheSheet() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("a")))

        viewModel.onIntent(ScanQRCodeScreenIntent.DismissSheet)

        assertDismissed()
    }

    @Test
    fun resumeScanningBehavesLikeDismissingTheSheet() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("a")))

        viewModel.onIntent(ScanQRCodeScreenIntent.ResumeScanning)

        assertDismissed()
    }

    @Test
    fun decodeAfterDismissIsAccepted() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("first")))
        viewModel.onIntent(ScanQRCodeScreenIntent.DismissSheet)
        startCamera()

        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("second")))

        val state = viewModel.state.value
        assertEquals(ScanResult("second"), state.sheetContent)
        assertTrue(state.isSheetVisible)
        assertTrue(viewModel.scanGate)
    }

    @Test
    fun resumeWithPermissionGrantedInSettingsStartsTheCamera() {
        viewModel.onIntent(ScanQRCodeScreenIntent.PermissionResult(granted = false))
        assertFalse(viewModel.state.value.hasPermission)
        permissionGranted = true

        viewModel.onIntent(ScanQRCodeScreenIntent.ScreenResumed)

        val state = viewModel.state.value
        assertTrue(state.hasPermission)
        assertFalse(state.isPermanentlyDenied)
        assertTrue(state.isCameraActive)
        assertTrue(state.isScanning)
        assertTrue(state.isCameraWarmingUp)
    }

    @Test
    fun resumeWhilePermissionIsStillDeniedKeepsTheDeniedScreen() {
        viewModel.onIntent(ScanQRCodeScreenIntent.PermissionResult(granted = false))
        val denied = viewModel.state.value

        viewModel.onIntent(ScanQRCodeScreenIntent.ScreenResumed)

        assertEquals(denied, viewModel.state.value)
    }

    @Test
    fun resumeBeforePermissionWasEverRequestedDoesNotStartTheCamera() {
        permissionGranted = true

        viewModel.onIntent(ScanQRCodeScreenIntent.ScreenResumed)

        assertEquals(ScanScreenState(), viewModel.state.value)
    }

    @Test
    fun resumeWithPermissionAlreadyGrantedLeavesARunningCameraAlone() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraReady)
        permissionGranted = true
        val running = viewModel.state.value

        viewModel.onIntent(ScanQRCodeScreenIntent.ScreenResumed)

        assertEquals(running, viewModel.state.value)
    }

    @Test
    fun previewStartedShowsTheWarmUpLoaderAgainAfterTheCameraWasReady() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraReady)
        assertFalse(viewModel.state.value.isCameraWarmingUp)

        viewModel.onIntent(ScanQRCodeScreenIntent.PreviewStarted)

        assertTrue(viewModel.state.value.isCameraWarmingUp)
    }

    @Test
    fun previewStartedWithTheCameraOffDoesNotShowTheLoader() {
        viewModel.onIntent(ScanQRCodeScreenIntent.PreviewStarted)
        assertFalse(viewModel.state.value.isCameraWarmingUp)

        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.CouldNotStart))
        viewModel.onIntent(ScanQRCodeScreenIntent.PreviewStarted)
        assertFalse(viewModel.state.value.isCameraWarmingUp)
    }

    private fun assertDismissed() {
        val state = viewModel.state.value
        assertFalse(viewModel.scanGate)
        assertFalse(state.isSheetVisible)
        assertNull(state.sheetContent)
        assertFalse(state.isCameraActive)
        assertFalse(state.isScanning)
        assertFalse(state.isCameraWarmingUp)
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
