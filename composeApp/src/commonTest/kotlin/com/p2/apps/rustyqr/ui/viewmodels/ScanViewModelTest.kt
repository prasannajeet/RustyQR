package com.p2.apps.rustyqr.ui.viewmodels

import com.p2.apps.rustyqr.bridge.CameraPermissions
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
    /** A [CameraPermissions] whose answers the test sets and which records what the ViewModel asked of it. */
    private class FakePermissions : CameraPermissions {
        var granted = false
        var permanentlyDenied = false
        var settingsOpened = 0
        val pendingRequests = mutableListOf<(Boolean) -> Unit>()

        override fun isGranted() = granted

        override fun isPermanentlyDenied() = permanentlyDenied

        override fun request(onResult: (Boolean) -> Unit) {
            pendingRequests += onResult
        }

        override fun openSettings() {
            settingsOpened++
        }
    }

    private val permissions = FakePermissions()
    private val viewModel = ScanViewModel(permissions)

    private fun startCamera() {
        // The state a granted permission produces, reached directly instead of through StartScanning.
        viewModel.onIntent(ScanQRCodeScreenIntent.PermissionResult(granted = true))
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
        permissions.permanentlyDenied = true
        viewModel.onIntent(ScanQRCodeScreenIntent.PermissionResult(granted = false))
        assertTrue(viewModel.state.value.isPermanentlyDenied)
        permissions.permanentlyDenied = false
        permissions.granted = true

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
        permissions.granted = true

        viewModel.onIntent(ScanQRCodeScreenIntent.ScreenResumed)

        assertEquals(ScanScreenState(), viewModel.state.value)
    }

    @Test
    fun resumeWithPermissionAlreadyGrantedLeavesARunningCameraAlone() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraReady)
        permissions.granted = true
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

    @Test
    fun failureWhileTheSheetShowsKeepsTheGateLockedUntilTheSheetIsDismissed() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("a")))

        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.CouldNotStart))
        assertTrue(viewModel.scanGate)

        viewModel.onIntent(ScanQRCodeScreenIntent.DismissSheet)
        assertFalse(viewModel.scanGate)
    }

    @Test
    fun resumeWhileBlockedWithTheResultSheetOpenDoesNotScan() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("first")))
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.BlockedByDevice))

        viewModel.onIntent(ScanQRCodeScreenIntent.ScreenResumed)
        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("second")))

        val state = viewModel.state.value
        assertFalse(state.isScanning)
        assertNull(state.cameraFailure)
        assertTrue(state.isSheetVisible)
        assertEquals(ScanResult("first"), state.sheetContent)
    }

    @Test
    fun retryWithTheResultSheetOpenDoesNotScan() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.FrameDecoded(ScanResult("first")))
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.CouldNotStart))

        viewModel.onIntent(ScanQRCodeScreenIntent.RetryCamera)

        assertFalse(viewModel.state.value.isScanning)
        assertEquals(ScanResult("first"), viewModel.state.value.sheetContent)
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
    fun resumeWhileBlockedByDeviceClearsTheFailureAndRestartsTheCamera() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.BlockedByDevice))
        assertEquals(CameraFailure.BlockedByDevice, viewModel.state.value.cameraFailure)

        viewModel.onIntent(ScanQRCodeScreenIntent.ScreenResumed)

        val state = viewModel.state.value
        assertNull(state.cameraFailure)
        assertTrue(state.isCameraActive)
        assertTrue(state.isScanning)
        assertTrue(state.isCameraWarmingUp)
    }

    @Test
    fun resumeWhileNotBlockedKeepsOtherFailures() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.NoCamera))
        val failed = viewModel.state.value

        viewModel.onIntent(ScanQRCodeScreenIntent.ScreenResumed)

        assertEquals(failed, viewModel.state.value)
    }

    @Test
    fun resumeWhileCouldNotStartKeepsTheFailureAndWaitsForTryAgain() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.CouldNotStart))
        val failed = viewModel.state.value

        viewModel.onIntent(ScanQRCodeScreenIntent.ScreenResumed)

        assertEquals(failed, viewModel.state.value)
    }

    @Test
    fun blockedByDeviceCannotBeRetriedWithTheRetryIntent() {
        startCamera()
        viewModel.onIntent(ScanQRCodeScreenIntent.CameraFailed(CameraFailure.BlockedByDevice))
        val blocked = viewModel.state.value

        viewModel.onIntent(ScanQRCodeScreenIntent.RetryCamera)

        assertEquals(blocked, viewModel.state.value)
    }

    @Test
    fun startScanningWithPermissionStartsTheCameraWithoutAsking() {
        permissions.granted = true

        viewModel.onIntent(ScanQRCodeScreenIntent.StartScanning)

        val state = viewModel.state.value
        assertTrue(state.hasPermission)
        assertTrue(state.permissionRequested)
        assertFalse(state.isPermanentlyDenied)
        assertTrue(state.isCameraActive)
        assertTrue(permissions.pendingRequests.isEmpty())
    }

    @Test
    fun startScanningWithoutPermissionAsksAndAppliesTheAnswer() {
        permissions.permanentlyDenied = true

        viewModel.onIntent(ScanQRCodeScreenIntent.StartScanning)
        assertEquals(1, permissions.pendingRequests.size)
        assertFalse(viewModel.state.value.permissionRequested)
        permissions.pendingRequests.single()(false)

        val state = viewModel.state.value
        assertFalse(state.hasPermission)
        assertTrue(state.permissionRequested)
        assertTrue(state.isPermanentlyDenied)
        assertFalse(state.isCameraActive)
    }

    @Test
    fun grantingThePermissionFromTheDialogStartsTheCamera() {
        viewModel.onIntent(ScanQRCodeScreenIntent.RequestPermission)
        permissions.pendingRequests.single()(true)

        assertTrue(viewModel.state.value.isCameraActive)
    }

    @Test
    fun resumeAfterGrantingInSettingsClearsThePermanentlyDeniedFlag() {
        permissions.permanentlyDenied = true
        viewModel.onIntent(ScanQRCodeScreenIntent.PermissionResult(granted = false))
        assertTrue(viewModel.state.value.isPermanentlyDenied)
        permissions.granted = true

        viewModel.onIntent(ScanQRCodeScreenIntent.ScreenResumed)

        assertFalse(viewModel.state.value.isPermanentlyDenied)
        assertTrue(viewModel.state.value.hasPermission)
    }

    @Test
    fun openSettingsIsForwardedToThePlatform() {
        viewModel.onIntent(ScanQRCodeScreenIntent.OpenSettings)

        assertEquals(1, permissions.settingsOpened)
    }

    @Test
    fun initialStateHasNoFailure() {
        assertEquals(ScanScreenState(), viewModel.state.value)
    }
}
