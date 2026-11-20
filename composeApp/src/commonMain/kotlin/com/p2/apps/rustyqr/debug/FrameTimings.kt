package com.p2.apps.rustyqr.debug

import arrow.core.Either
import com.p2.apps.rustyqr.bridge.logInfo
import kotlin.time.TimeSource

private const val LOG_TAG = "FrameTimings"
private const val BATCH_SIZE = 100
private const val MEDIAN_PERCENT = 50
private const val P95_PERCENT = 95
private const val PERCENT_SCALE = 100
private const val NANOS_PER_MICRO = 1000L

private val monotonicOrigin = TimeSource.Monotonic.markNow()

/** A source of monotonic nanoseconds. A `fun interface` returning a primitive, so reading it never boxes. */
internal fun interface NanoClock {
    fun nowNanos(): Long
}

/** Nanoseconds on the monotonic clock, counted from the first call. */
private fun monotonicNanos(): Long = monotonicOrigin.elapsedNow().inWholeNanoseconds

/** The per-frame steps that are timed. [label] is the name used in the log. */
internal enum class FrameStage(
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
internal fun percentile(
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
 * Call order per frame: [startFrame], [endCopy], [endRust], [endFrame]. A frame that returns early skips the remaining
 * calls. [FrameStage.TOTAL] and the frame count only include frames that reached the Rust call, so frames dropped
 * before decoding (not scanning, unusable plane) do not skew the batch.
 *
 * Timings run when [logFrameTimings] is true, in any build type. When it is false, every method returns on its first
 * statement: the clock is never read and nothing is allocated. Callers additionally guard each call with the
 * `LOG_FRAME_TIMINGS` constant, so the compiler removes the calls from the frame path in committed builds.
 *
 * Not thread-safe by design: use one instance per analysis thread. Each camera composition creates its own analyzer
 * thread, so a new screen entry gets its own instance and an old and a new thread never share state. A frame that is
 * still in flight on an old thread only affects its own instance. No method throws.
 */
internal class FrameTimings(
    logFrameTimings: Boolean = LOG_FRAME_TIMINGS,
    private val clock: NanoClock = NanoClock(::monotonicNanos),
    private val log: (String) -> Unit = { logInfo(LOG_TAG, it) },
) {
    val isEnabled: Boolean = logFrameTimings

    private val samples = Array(if (isEnabled) FrameStage.entries.size else 0) { LongArray(BATCH_SIZE) }
    private val counts = IntArray(samples.size)
    private var frames = 0
    private var reachedRust = false
    private var frameStart = 0L
    private var stageStart = 0L

    /** Marks the analyzer entry. */
    fun startFrame() {
        if (!isEnabled) return
        val now = clock.nowNanos()
        frameStart = now
        stageStart = now
        reachedRust = false
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
        reachedRust = true
    }

    /**
     * Ends the frame: records [FrameStage.TOTAL] and logs when 100 frames have completed. Ignored when the frame never
     * reached the Rust call.
     */
    fun endFrame() {
        if (!isEnabled || !reachedRust) return
        reachedRust = false
        add(FrameStage.TOTAL, clock.nowNanos() - frameStart)
        frames++
        if (frames >= BATCH_SIZE) flush()
    }

    private fun lap(stage: FrameStage) {
        val now = clock.nowNanos()
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
