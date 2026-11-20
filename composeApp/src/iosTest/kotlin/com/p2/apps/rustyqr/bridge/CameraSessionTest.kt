package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import com.p2.apps.rustyqr.model.CameraFailure
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.darwin.NSObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class NoOpFrameDelegate :
    NSObject(),
    AVCaptureVideoDataOutputSampleBufferDelegateProtocol

class CameraSessionTest {
    /** Runs on the simulator, which has no camera. */
    @Test
    fun configureSessionWithoutCameraReportsNoCameraAndAddsNothing() {
        if (backVideoDevice() != null) return
        val session = AVCaptureSession()

        val result = configureSession(session, NoOpFrameDelegate())

        assertEquals(Either.Left(CameraFailure.NoCamera), result)
        assertTrue(session.inputs.isEmpty())
        assertTrue(session.outputs.isEmpty())
    }
}
