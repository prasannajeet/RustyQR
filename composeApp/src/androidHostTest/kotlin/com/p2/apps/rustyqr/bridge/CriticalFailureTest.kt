package com.p2.apps.rustyqr.bridge

import androidx.camera.core.CameraState
import com.p2.apps.rustyqr.model.CameraFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CriticalFailureTest {
    private fun stateWithError(code: Int): CameraState {
        val error = CameraState.StateError.create(code)
        return CameraState.create(CameraState.Type.CLOSED, error)
    }

    @Test
    fun aStateWithoutAnErrorReportsNothing() {
        assertNull(criticalFailure(CameraState.create(CameraState.Type.OPEN), stale = null))
    }

    @Test
    fun theStaleStateIsIgnoredEvenWhenItIsCritical() {
        val stale = stateWithError(CameraState.ERROR_CAMERA_FATAL_ERROR)

        assertNull(criticalFailure(stale, stale = stale))
    }

    @Test
    fun anEqualButDifferentStateObjectIsNotStale() {
        val stale = stateWithError(CameraState.ERROR_CAMERA_FATAL_ERROR)
        val fresh = stateWithError(CameraState.ERROR_CAMERA_FATAL_ERROR)

        assertEquals(CameraFailure.CouldNotStart, criticalFailure(fresh, stale = stale))
    }

    @Test
    fun aDisabledCameraIsBlockedByTheDevice() {
        val state = stateWithError(CameraState.ERROR_CAMERA_DISABLED)

        assertEquals(CameraFailure.BlockedByDevice, criticalFailure(state, stale = null))
    }

    @Test
    fun otherCriticalErrorsCouldNotStart() {
        listOf(CameraState.ERROR_CAMERA_FATAL_ERROR, CameraState.ERROR_STREAM_CONFIG).forEach { code ->
            assertEquals(CameraFailure.CouldNotStart, criticalFailure(stateWithError(code), stale = null))
        }
    }

    @Test
    fun recoverableErrorsReportNothing() {
        listOf(
            CameraState.ERROR_MAX_CAMERAS_IN_USE,
            CameraState.ERROR_CAMERA_IN_USE,
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR,
        ).forEach { code ->
            assertNull(criticalFailure(stateWithError(code), stale = null))
        }
    }
}
