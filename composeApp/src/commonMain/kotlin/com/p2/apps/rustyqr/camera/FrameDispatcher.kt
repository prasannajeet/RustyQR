package com.p2.apps.rustyqr.camera

import com.p2.apps.rustyqr.model.ScanResult
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Routes camera-thread events to the caller's callbacks.
 *
 * The camera and its analyzer outlive any single composition, so nothing here may hold a callback or flag
 * captured at creation. Each value is a provider that is called at the moment it is needed, which lets the
 * composable hand in `rememberUpdatedState` reads and always have the latest value win. Callbacks are resolved on
 * the main thread, when the posted work runs, so a callback replaced while a frame was in flight is still honoured.
 *
 * @param isScanning Whether frames should be decoded right now. Called on the analysis thread for every frame, so
 *   it must be safe to read from any thread (snapshot state or an atomic).
 * @param currentOnQrDecoded Provider of the current decode callback, called on the main thread.
 * @param currentOnCameraReady Provider of the current ready callback, called on the main thread.
 * @param postToMain Runs the given work on the main thread.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class FrameDispatcher(
    private val isScanning: () -> Boolean,
    private val currentOnQrDecoded: () -> (ScanResult) -> Unit,
    private val currentOnCameraReady: () -> () -> Unit,
    private val postToMain: (() -> Unit) -> Unit,
) {
    private val readyFired = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * Call for every delivered frame, on the analysis thread. Fires the ready callback once, on the first frame
     * even while not scanning, so the "Starting camera" overlay clears as soon as the preview is live.
     *
     * @return true when the frame should be decoded; always false after [close].
     */
    fun onFrame(): Boolean {
        if (closed.load()) return false
        if (readyFired.compareAndSet(expectedValue = false, newValue = true)) {
            postToMain { if (!closed.load()) currentOnCameraReady()() }
        }
        return isScanning()
    }

    /** Delivers [result] to the latest decode callback on the main thread, unless [close] was called. */
    fun deliver(result: ScanResult) {
        if (closed.load()) return
        postToMain { if (!closed.load()) currentOnQrDecoded()(result) }
    }

    /**
     * Stops all further callbacks and posts nothing more. Call it on the thread [postToMain] runs work on; only
     * then is work that was already posted guaranteed to be dropped.
     */
    fun close() {
        closed.store(true)
    }
}
