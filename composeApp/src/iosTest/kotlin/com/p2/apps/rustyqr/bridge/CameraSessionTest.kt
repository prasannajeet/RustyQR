package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import com.p2.apps.rustyqr.camera.FrameDispatcher
import com.p2.apps.rustyqr.model.CameraFailure
import platform.AVFoundation.AVCaptureSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraSessionTest {
    /** Runs on the simulator, which has no camera. */
    @Test
    fun configureSessionWithoutCameraReportsNoCameraAndAddsNothing() {
        if (backVideoDevice() != null) return
        val dispatcher =
            FrameDispatcher(
                isScanning = { false },
                onQrDecoded = {},
                onCameraReady = {},
                onCameraError = {},
                postToMain = { it() },
            )
        val session = AVCaptureSession()

        val result = configureSession(session, FrameDelegate(dispatcher))

        assertEquals(Either.Left(CameraFailure.NoCamera), result)
        assertTrue(session.inputs.isEmpty())
        assertTrue(session.outputs.isEmpty())
    }
}
