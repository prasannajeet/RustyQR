package com.p2.apps.rustyqr.debug

import arrow.core.Either
import arrow.core.raise.either
import com.p2.apps.rustyqr.bridge.FrameFiles
import com.p2.apps.rustyqr.bridge.frameFiles
import com.p2.apps.rustyqr.bridge.isDebugBuild
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Clock

private const val MANIFEST_NAME = "manifest.jsonl"
private const val UNSEEDED = 0

/**
 * Saves camera frames for use as test data and benchmark input: the Y plane exactly as delivered (row padding
 * included) plus one line in `manifest.jsonl`.
 *
 * Recording runs only when [isEnabled], which defaults to `CAPTURE_FRAMES && isDebugBuild`. When it is false,
 * [record] returns on its first statement: nothing is allocated, the policy is not consulted and [files] is never
 * initialised. Callers that must build the arguments (the iOS plane copy) check [isEnabled] first.
 *
 * Frames are numbered by manifest line, seeded from the manifest once per launch, so files from earlier launches
 * are never overwritten. If the cache directory or the manifest cannot be used, the frame is skipped.
 *
 * Writes are blocking file I/O on the caller's thread, which is a camera analysis thread. Failed writes are
 * dropped, since this is a debug aid. Create one instance per app launch: it holds the per-launch frame budget.
 * Thread-safe: analysis threads of consecutive screen entries can overlap.
 */
@OptIn(ExperimentalAtomicApi::class)
class FrameRecorder(
    private val platform: String,
    private val files: Lazy<Either<String, FrameFiles>> = lazy { frameFiles() },
    val isEnabled: Boolean = CAPTURE_FRAMES && isDebugBuild,
    private val policy: CapturePolicy = CapturePolicy(),
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val nextIndex = AtomicInt(UNSEEDED)

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
                val name = "$platform-${width}x$height-s$rowStride-$index.y"
                val layout = FrameLayout(width, height, rowStride)
                val entry = FrameManifestEntry(name, platform, layout, clockMillis(), content)
                store.writeBytes(name, luma).bind()
                store.appendLine(MANIFEST_NAME, entry.toJsonLine()).bind()
            }
        }
    }

    private fun claimIndex(store: FrameFiles): Either<String, Int> {
        val seeded =
            if (nextIndex.load() == UNSEEDED) {
                store.countLines(MANIFEST_NAME).onRight { nextIndex.compareAndSet(UNSEEDED, it + 1) }.map { }
            } else {
                Either.Right(Unit)
            }
        return seeded.map { nextIndex.fetchAndIncrement() }
    }
}
