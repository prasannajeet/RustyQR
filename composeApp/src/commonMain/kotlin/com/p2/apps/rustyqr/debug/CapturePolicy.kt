package com.p2.apps.rustyqr.debug

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

private const val SAMPLE_INTERVAL = 15
private const val MAX_FRAMES = 40

/**
 * Decides which analysed frames are worth saving: every [sampleInterval]th frame, plus every frame that decoded
 * successfully, up to [maxFrames] saved frames per instance (one instance lives for the whole app launch).
 *
 * Thread-safe: the camera analysis thread is recreated per screen entry, so an old and a new thread can overlap.
 * Open only so tests can observe whether it was consulted.
 */
@OptIn(ExperimentalAtomicApi::class)
open class CapturePolicy(
    private val sampleInterval: Int = SAMPLE_INTERVAL,
    private val maxFrames: Int = MAX_FRAMES,
) {
    private val analysedFrames = AtomicInt(0)
    private val acceptedFrames = AtomicInt(0)

    /** Registers one analysed frame and returns true when it should be saved. */
    open fun accept(decoded: Boolean): Boolean {
        val analysed = analysedFrames.incrementAndFetch()
        return (decoded || analysed % sampleInterval == 0) && reserveSlot()
    }

    private fun reserveSlot(): Boolean {
        var reserved = false
        var open = true
        while (open) {
            val current = acceptedFrames.load()
            open = current < maxFrames
            if (open && acceptedFrames.compareAndSet(current, current + 1)) {
                reserved = true
                open = false
            }
        }
        return reserved
    }
}
