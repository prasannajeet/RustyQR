package com.p2.apps.rustyqr.debug

import com.p2.apps.rustyqr.bridge.FrameFiles
import com.p2.apps.rustyqr.bridge.frameFiles
import com.p2.apps.rustyqr.bridge.isDebugBuild
import kotlin.time.Clock

private const val MANIFEST_NAME = "manifest.jsonl"

/**
 * Saves camera frames for use as test data and benchmark input: the Y plane exactly as delivered (row padding
 * included) plus one line in `manifest.jsonl`.
 *
 * Recording runs only when [isEnabled], which defaults to `isDebugBuild && CAPTURE_FRAMES`. When it is false,
 * [record] returns on its first statement: nothing is allocated, the policy is not consulted and [files] is never
 * initialised. Callers that must build the arguments (the iOS plane copy) check [isEnabled] first.
 *
 * Writes are blocking file I/O on the caller's thread, which is the camera analysis thread. Failed writes are
 * dropped, since this is a debug aid. Create one instance per app launch: it holds the per-launch frame budget.
 */
class FrameRecorder(
    private val platform: String,
    private val files: Lazy<FrameFiles> = lazy { frameFiles() },
    val isEnabled: Boolean = isDebugBuild && CAPTURE_FRAMES,
    private val policy: CapturePolicy = CapturePolicy(),
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
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
        val layout = FrameLayout(width, height, rowStride)
        val name = "$platform-${width}x$height-s$rowStride-${policy.analysedFrames}.y"
        val entry = FrameManifestEntry(name, platform, layout, clockMillis(), content)
        val frameFiles = files.value
        // Only list a frame in the manifest when its bytes were saved.
        frameFiles
            .writeBytes(name, luma)
            .onRight { frameFiles.appendLine(MANIFEST_NAME, entry.toJsonLine()) }
    }
}
