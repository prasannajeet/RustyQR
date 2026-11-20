package com.p2.apps.rustyqr.debug

import arrow.core.Either
import arrow.core.raise.either
import com.p2.apps.rustyqr.bridge.FrameFiles
import com.p2.apps.rustyqr.bridge.frameFiles
import com.p2.apps.rustyqr.bridge.isDebugBuild
import com.p2.apps.rustyqr.bridge.logInfo
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Clock

private const val MANIFEST_NAME = "manifest.jsonl"
private const val UNSEEDED = 0
private const val FRAME_SUFFIX = ".y"
private const val LOG_TAG = "FrameRecorder"

@OptIn(ExperimentalAtomicApi::class)
private val conflictWarned = AtomicBoolean(false)

/**
 * Whether frame recording may run. Timings win over recording: it needs [captureFrames], no [logFrameTimings] and a
 * debug build. The constants are checked first, so when recording is off [isDebug] is never evaluated. When both flags
 * are true, [warn] is called once per [warnedOnce] guard (process-wide by default).
 */
@OptIn(ExperimentalAtomicApi::class)
internal fun recordingEnabled(
    captureFrames: Boolean = CAPTURE_FRAMES,
    logFrameTimings: Boolean = LOG_FRAME_TIMINGS,
    isDebug: () -> Boolean = { isDebugBuild },
    warn: (String) -> Unit = { logInfo(LOG_TAG, it) },
    warnedOnce: AtomicBoolean = conflictWarned,
): Boolean {
    if (captureFrames && logFrameTimings && warnedOnce.compareAndSet(expectedValue = false, newValue = true)) {
        warn("CAPTURE_FRAMES is ignored because LOG_FRAME_TIMINGS is also true; frames are timed, not recorded")
    }
    return captureFrames && !logFrameTimings && isDebug()
}

/** Highest `n` among names ending in `-<n>.y`; names that do not parse are ignored. 0 when there is none. */
private fun highestIndex(names: List<String>): Int =
    names
        .filter { it.endsWith(FRAME_SUFFIX) }
        .mapNotNull { it.removeSuffix(FRAME_SUFFIX).substringAfterLast('-').toIntOrNull() }
        .maxOrNull() ?: 0

/**
 * Saves camera frames for use as test data and benchmark input: the Y plane exactly as delivered (row padding
 * included) plus one line in `manifest.jsonl`.
 *
 * Recording runs only when [isEnabled], which defaults to `CAPTURE_FRAMES && !LOG_FRAME_TIMINGS && isDebugBuild`
 * (see [recordingEnabled]; timings win over recording). When it is false,
 * [record] returns on its first statement: nothing is allocated, the policy is not consulted and [files] is never
 * initialised. Callers that must build the arguments (the iOS plane copy) check [isEnabled] first.
 *
 * Frames are numbered one above the highest existing `-<n>.y` file, seeded once per launch, so no existing file is
 * ever overwritten, even after failed saves left gaps. If the cache directory cannot be used or listed, the frame
 * is skipped.
 *
 * Writes are blocking file I/O on the caller's thread, which is a camera analysis thread. Failed writes are
 * dropped, since this is a debug aid. Create one instance per app launch: it holds the per-launch frame budget.
 * Thread-safe: analysis threads of consecutive screen entries can overlap.
 */
@OptIn(ExperimentalAtomicApi::class)
class FrameRecorder(
    private val platform: String,
    private val files: Lazy<Either<String, FrameFiles>> = lazy { frameFiles() },
    val isEnabled: Boolean = recordingEnabled(),
    private val policy: CapturePolicy = CapturePolicy(),
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val nextIndex = AtomicInt(UNSEEDED)

    /** True when a frame offered now could still be saved: recording is on and the frame budget is not spent. */
    val wantsFrames: Boolean
        get() = isEnabled && policy.hasBudget()

    /**
     * Offers one analysed frame. [luma] is the Y plane as delivered, [rowStride] its bytes per row, and [content]
     * the decoded text or null when nothing decoded.
     */
    fun record(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        content: String?,
    ) {
        if (!isEnabled || !policy.accept(decoded = content != null)) return
        files.value.onRight { store ->
            either {
                // The index is claimed atomically, so it is unique even when threads overlap. A failed save burns
                // it, which leaves a gap but never a manifest line that points at replaced bytes.
                val index = claimIndex(store).bind()
                val name = "$platform-${width}x$height-s$rowStride-$index$FRAME_SUFFIX"
                val layout = FrameLayout(width, height, rowStride)
                val entry = FrameManifestEntry(name, platform, layout, clockMillis(), content)
                store.writeBytes(name, luma).bind()
                store.appendLine(MANIFEST_NAME, entry.toJsonLine()).bind()
            }
        }
    }

    private fun claimIndex(store: FrameFiles): Either<String, Int> =
        either {
            if (nextIndex.load() == UNSEEDED) {
                nextIndex.compareAndSet(UNSEEDED, 1 + highestIndex(store.listNames().bind()))
            }
            nextIndex.fetchAndIncrement()
        }
}
