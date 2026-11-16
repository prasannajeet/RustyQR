package com.p2.apps.rustyqr.debug

private const val SAMPLE_INTERVAL = 15
private const val MAX_FRAMES = 40

/**
 * Decides which analysed frames are worth saving: every [sampleInterval]th frame, plus every frame that decoded
 * successfully, up to [maxFrames] saved frames per instance (one instance lives for the whole app launch).
 *
 * Not thread-safe; call it from the single analysis thread.
 */
class CapturePolicy(
    private val sampleInterval: Int = SAMPLE_INTERVAL,
    private val maxFrames: Int = MAX_FRAMES,
) {
    /** Number of frames passed to [accept] so far, counting the current one. */
    var analysedFrames: Int = 0
        private set

    private var acceptedFrames = 0

    /** Registers one analysed frame and returns true when it should be saved. */
    fun accept(decoded: Boolean): Boolean {
        analysedFrames++
        val wanted = decoded || analysedFrames % sampleInterval == 0
        val accepted = wanted && acceptedFrames < maxFrames
        if (accepted) acceptedFrames++
        return accepted
    }
}
