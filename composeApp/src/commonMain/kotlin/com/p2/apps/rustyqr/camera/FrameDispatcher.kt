package com.p2.apps.rustyqr.camera

import com.p2.apps.rustyqr.model.CameraFailure
import com.p2.apps.rustyqr.model.ScanResult
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Where a [FrameDispatcher] is in its life: delivering, stopped by a camera failure, or closed for good. */
private enum class Phase { Live, Failed, Closed }

/**
 * Routes camera-thread events to the caller's callbacks.
 *
 * The camera and its analyzer outlive any single composition, so nothing here may hold a callback or flag
 * captured at creation. Each callback is a plain lambda that is invoked at the moment it is needed, which lets the
 * composable hand in lambdas that read `rememberUpdatedState` and always have the latest value win. Callbacks run
 * on the main thread, when the posted work runs, so a callback replaced while a frame was in flight is still
 * honoured.
 *
 * @param isScanning Whether frames should be decoded right now. Called on the analysis thread for every frame, so
 *   it must be safe to read from any thread (snapshot state or an atomic).
 * @param onQrDecoded The decode callback, invoked on the main thread.
 * @param onCameraReady The ready callback, invoked at most once on the main thread.
 * @param onCameraError The failure callback, invoked at most once on the main thread.
 * @param postToMain Runs the given work on the main thread.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class FrameDispatcher(
    private val isScanning: () -> Boolean,
    private val onQrDecoded: (ScanResult) -> Unit,
    private val onCameraReady: () -> Unit,
    private val onCameraError: (CameraFailure) -> Unit,
    private val postToMain: (() -> Unit) -> Unit,
) {
    private val readyFired = AtomicBoolean(false)
    private val phase = AtomicReference(Phase.Live)

    /**
     * Call for every delivered frame, on the analysis thread. Fires the ready callback once, on the first frame
     * even while not scanning, so the "Starting camera" overlay clears as soon as the preview is live.
     *
     * @return true when the frame should be decoded; always false after [close] or [fail], and no ready callback
     *   is posted after [fail].
     */
    fun onFrame(): Boolean {
        if (!isLive()) return false
        if (readyFired.compareAndSet(expectedValue = false, newValue = true)) {
            postToMain { if (isLive()) onCameraReady() }
        }
        return isScanning()
    }

    /**
     * Delivers [result] to the decode callback on the main thread, unless [close] or [fail] was called. A decode
     * in flight when a failure lands is dropped, so no result sheet opens over the failure screen.
     */
    fun deliver(result: ScanResult) {
        if (!isLive()) return
        postToMain { if (isLive()) onQrDecoded(result) }
    }

    /**
     * Reports that the camera could not start. Delivered to the failure callback on the main thread, once;
     * later calls and calls after [close] are dropped. After this, [onFrame] stops reporting frames.
     */
    fun fail(failure: CameraFailure) {
        if (phase.compareAndSet(expectedValue = Phase.Live, newValue = Phase.Failed)) {
            postToMain { if (phase.load() != Phase.Closed) onCameraError(failure) }
        }
    }

    /**
     * Stops all further callbacks and posts nothing more. Call it on the thread [postToMain] runs work on; only
     * then is work that was already posted guaranteed to be dropped.
     */
    fun close() {
        phase.store(Phase.Closed)
    }

    private fun isLive(): Boolean = phase.load() == Phase.Live
}
