package com.p2.apps.rustyqr.debug

import arrow.core.Either
import com.p2.apps.rustyqr.bridge.logInfo
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

private const val LOG_TAG = "FrameTimings"
private const val BATCH_SIZE = 100
private const val MEDIAN_PERCENT = 50
private const val P95_PERCENT = 95
private const val PERCENT_SCALE = 100
private const val NANOS_PER_MICRO = 1000L

private val monotonicOrigin = TimeSource.Monotonic.markNow()

/** Nanoseconds on the monotonic clock, counted from the first call; a plain [Long], so reading it allocates nothing. */
private fun monotonicNanos(): Long = monotonicOrigin.elapsedNow().inWholeNanoseconds

@OptIn(ExperimentalAtomicApi::class)
private val conflictWarned = AtomicBoolean(false)

/** The per-frame steps that are timed. [label] is the name used in the log. */
enum class FrameStage(
    val label: String,
) {
    /** Bytes copied out of the camera buffer, including row packing. */
    COPY("copy"),

    /** The `QrBridge.decodeQrFromRaw` call when it found a code. */
    RUST_HIT("rust_hit"),

    /** The `QrBridge.decodeQrFromRaw` call when it found no code. */
    RUST_MISS("rust_miss"),

    /** From analyzer entry to return. */
    TOTAL("total"),
}

/**
 * The [percent]th percentile of [samples] by the nearest-rank method: sort ascending, take the value at rank
 * `ceil(percent / 100 * n)`, counting from 1. The result is always one of the samples, never an interpolation, so
 * the median (50th percentile) of an even count is the lower of the two middle values. [percent] is clamped to
 * 1..100. Returns null when [samples] is empty. The input need not be sorted and is not modified.
 */
fun percentile(
    samples: List<Long>,
    percent: Int,
): Long? {
    val sorted = samples.sorted()
    val rank = (percent.coerceIn(1, PERCENT_SCALE) * sorted.size + PERCENT_SCALE - 1) / PERCENT_SCALE
    return sorted.getOrNull(rank - 1)
}

/**
 * Times the steps of the camera analyzer and, every 100 completed frames, logs the count, median and 95th percentile
 * of each [FrameStage] (in microseconds) in one line, then starts over.
 *
 * Call order per frame: [startFrame], [endCopy], [endRust], [endFrame]. A frame that returns early simply skips the
 * remaining calls; the next [startFrame] discards its partial laps.
 *
 * Timings run when [logFrameTimings] is true and [captureFrames] is false, in any build type. If both are true, no
 * timings are taken and one warning is logged per process. When not [isEnabled], every method returns on its first
 * statement: the clock is never read, nothing is allocated and no lambda runs. [isEnabled] is built from two
 * compile-time constants.
 *
 * Not thread-safe by design: use one instance per analysis thread. Each camera composition creates its own analyzer
 * thread, so a new screen entry gets its own instance and an old and a new thread never share state. A frame that is
 * still in flight on an old thread only affects its own instance. No method throws.
 */
@OptIn(ExperimentalAtomicApi::class)
class FrameTimings(
    logFrameTimings: Boolean = LOG_FRAME_TIMINGS,
    captureFrames: Boolean = CAPTURE_FRAMES,
    private val nowNanos: () -> Long = ::monotonicNanos,
    private val log: (String) -> Unit = { logInfo(LOG_TAG, it) },
) {
    val isEnabled: Boolean = logFrameTimings && !captureFrames

    private val samples = Array(if (isEnabled) FrameStage.entries.size else 0) { LongArray(BATCH_SIZE) }
    private val counts = IntArray(samples.size)
    private var frames = 0
    private var frameStart = 0L
    private var stageStart = 0L

    init {
        if (logFrameTimings && captureFrames && conflictWarned.compareAndSet(expectedValue = false, newValue = true)) {
            log("LOG_FRAME_TIMINGS is ignored because CAPTURE_FRAMES is also true; frames are recorded, not timed")
        }
    }

    /** Marks the analyzer entry. */
    fun startFrame() {
        if (!isEnabled) return
        val now = nowNanos()
        frameStart = now
        stageStart = now
    }

    /** Ends the [FrameStage.COPY] step. */
    fun endCopy() {
        if (!isEnabled) return
        lap(FrameStage.COPY)
    }

    /** Ends the Rust step: [FrameStage.RUST_HIT] when [result] is a right, otherwise [FrameStage.RUST_MISS]. */
    fun endRust(result: Either<*, *>) {
        if (!isEnabled) return
        lap(if (result.isRight()) FrameStage.RUST_HIT else FrameStage.RUST_MISS)
    }

    /** Ends the frame: records [FrameStage.TOTAL] and logs when 100 frames have completed. */
    fun endFrame() {
        if (!isEnabled) return
        add(FrameStage.TOTAL, nowNanos() - frameStart)
        frames++
        if (frames >= BATCH_SIZE) flush()
    }

    private fun lap(stage: FrameStage) {
        val now = nowNanos()
        add(stage, now - stageStart)
        stageStart = now
    }

    private fun add(
        stage: FrameStage,
        nanos: Long,
    ) {
        val count = counts[stage.ordinal]
        if (count < BATCH_SIZE) {
            samples[stage.ordinal][count] = nanos
            counts[stage.ordinal] = count + 1
        }
    }

    private fun flush() {
        val line =
            FrameStage.entries.joinToString(separator = " | ", prefix = "last $frames frames: ") { stage ->
                val values = samples[stage.ordinal].asList().subList(0, counts[stage.ordinal])
                val median = percentile(values, MEDIAN_PERCENT)?.div(NANOS_PER_MICRO)
                val p95 = percentile(values, P95_PERCENT)?.div(NANOS_PER_MICRO)
                "${stage.label} n=${values.size} median=${median ?: "-"}us p95=${p95 ?: "-"}us"
            }
        counts.fill(0)
        frames = 0
        log(line)
    }
}
