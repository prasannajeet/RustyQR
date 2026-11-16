@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.p2.apps.rustyqr.bridge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import arrow.core.Either
import com.p2.apps.rustyqr.debug.FrameRecorder
import com.p2.apps.rustyqr.debug.FrameTimings
import com.p2.apps.rustyqr.model.ScanResult
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.plus
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferGetWidthOfPlane
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t

/**
 * iOS actual — AVFoundation camera preview with live QR decoding.
 *
 * Architecture:
 * - [CameraPreviewUIView]: [UIView] subclass hosting [AVCaptureVideoPreviewLayer].
 *   Overrides [layoutSubviews] to keep the preview layer filling the view bounds.
 * - [FrameDelegate]: [NSObject] implementing
 *   [AVCaptureVideoDataOutputSampleBufferDelegateProtocol].
 *   Extracts the Y-plane from each sample buffer and calls [QrBridge.decodeQrFromRaw].
 * - [AVCaptureSession] uses the `.photo` preset and the back camera.
 *   Session start/stop runs on a dedicated serial queue.
 *   Frame delivery runs on a separate serial queue to avoid blocking the main thread.
 */
@Composable
actual fun CameraPreview(
    isScanning: Boolean,
    onQrDecoded: (ScanResult) -> Unit,
    onCameraReady: () -> Unit,
) {
    val session = remember { AVCaptureSession() }
    val sessionQueue: dispatch_queue_t =
        remember { dispatch_queue_create("com.p2.apps.rustyqr.session", null) }

    // rememberUpdatedState ensures the delegate always calls the latest lambdas,
    // even when the caller recomposes with new callback references.
    val latestOnDecoded by rememberUpdatedState(onQrDecoded)
    val latestOnReady by rememberUpdatedState(onCameraReady)
    val delegate =
        remember {
            FrameDelegate(
                onQrDecoded = { result -> latestOnDecoded(result) },
                onCameraReady = { latestOnReady() },
            )
        }

    // Propagate isScanning to the delegate on every recomposition.
    // Wrapped in SideEffect to keep side effects out of the composable body.
    SideEffect { delegate.isScanning = isScanning }

    UIKitView(
        factory = {
            val previewView = CameraPreviewUIView(session)
            configureSession(session, delegate)
            dispatch_async(sessionQueue) { session.startRunning() }
            previewView
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = {
            dispatch_async(sessionQueue) { session.stopRunning() }
        },
    )
}

// ---------------------------------------------------------------------------
// Preview UIView
// ---------------------------------------------------------------------------

/**
 * [UIView] that hosts an [AVCaptureVideoPreviewLayer] as its primary layer.
 * The preview layer fills the view bounds and is kept in sync via [layoutSubviews].
 */
private class CameraPreviewUIView(
    session: AVCaptureSession,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val previewLayer: AVCaptureVideoPreviewLayer =
        AVCaptureVideoPreviewLayer(session = session).also {
            it.videoGravity = AVLayerVideoGravityResizeAspectFill
            layer.addSublayer(it)
        }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
    }
}

// ---------------------------------------------------------------------------
// Frame delegate
// ---------------------------------------------------------------------------

/**
 * Receives camera frames, extracts the Y-plane (luma), and calls
 * [QrBridge.decodeQrFromRaw].
 *
 * [isScanning] is read on every frame.  Set it to false to pause analysis without
 * stopping the session (avoids the black-frame flash on resume).
 *
 * [isScanning] is a plain Boolean var. The Kotlin/Native new memory model provides
 * release/acquire semantics for shared mutable state — a single Boolean is safe
 * without atomics.
 */
private class FrameDelegate(
    private val onQrDecoded: (ScanResult) -> Unit,
    private val onCameraReady: () -> Unit,
) : NSObject(),
    AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    var isScanning: Boolean = true
    private var readyFired: Boolean = false

    // Touched only on this delegate's serial analysis queue; each composition has its own delegate and queue.
    private val timings = FrameTimings()

    override fun captureOutput(
        output: platform.AVFoundation.AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        // Fire the ready callback on the first delivered frame (even if not scanning),
        // so the "Starting camera…" overlay clears as soon as the preview is live.
        if (!readyFired) {
            readyFired = true
            dispatch_async(dispatch_get_main_queue()) { onCameraReady() }
        }
        if (!isScanning) return
        val pixelBuffer = didOutputSampleBuffer?.let { CMSampleBufferGetImageBuffer(it) } ?: return
        decodeFrame(pixelBuffer)
    }

    private fun decodeFrame(pixelBuffer: CVPixelBufferRef) {
        timings.startFrame()
        CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
        // Copy only while locked; decoding and recording work on the copies.
        val frame =
            try {
                copyLumaPlane(pixelBuffer, keepPadded = frameRecorder.wantsFrames)
            } finally {
                CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
            }
        if (frame == null) return
        timings.endCopy()
        val decoded = QrBridge.decodeQrFromRaw(frame.bytes, frame.width, frame.height)
        timings.endRust(decoded)
        decoded.onRight { result ->
            dispatch_async(dispatch_get_main_queue()) { onQrDecoded(result) }
        }
        // After the result is dispatched, so the blocking write cannot delay it. Null unless recording is on.
        frame.padded?.let { padded ->
            frameRecorder.record(padded, frame.width, frame.height, frame.bytesPerRow, decoded.getOrNull()?.content)
        }
        timings.endFrame()
    }

    /**
     * Copies plane 0 of the (locked) [pixelBuffer] into a tightly packed luma array, or null if unusable.
     * With [keepPadded], also copies the plane as delivered, row padding included.
     */
    private fun copyLumaPlane(
        pixelBuffer: CVPixelBufferRef,
        keepPadded: Boolean,
    ): LumaFrame? {
        val baseAddress = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0u)
        val width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0u).toInt()
        val height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0u).toInt()
        val bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0u).toInt()

        if (baseAddress == null || width <= 0 || height <= 0) return null

        // Row-by-row copy: bytesPerRow >= width due to row-stride padding.
        // Advance the pointer per row to avoid reading the entire buffer from index 0
        // on each iteration (which would be O(n²) in frame size).
        val dst = ByteArray(width * height)
        val bytePtr = baseAddress.reinterpret<ByteVar>()
        for (row in 0 until height) {
            val rowStart = (bytePtr + row.toLong() * bytesPerRow.toLong()) ?: continue
            rowStart.readBytes(width).copyInto(dst, destinationOffset = row * width)
        }
        val padded = if (keepPadded) bytePtr.readBytes(bytesPerRow * height) else null
        return LumaFrame(dst, width, height, bytesPerRow, padded)
    }
}

/** A packed luma copy; [padded] is the plane as delivered (row padding included), only set when recording. */
private class LumaFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val bytesPerRow: Int,
    val padded: ByteArray?,
)

/** Process-scoped so the debug frame budget is per app launch. */
private val frameRecorder = FrameRecorder(platform = "ios")

// ---------------------------------------------------------------------------
// Session configuration
// ---------------------------------------------------------------------------

private fun authorizedVideoDevice(): AVCaptureDevice? {
    val authStatus = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
    if (authStatus != AVAuthorizationStatusAuthorized) return null
    return AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
}

/** Returns null when the OS reports an error (e.g. device access denied after the authorization check). */
private fun createDeviceInput(device: AVCaptureDevice): AVCaptureDeviceInput? =
    // AVCaptureDeviceInput throws a Kotlin exception when Objective-C sets the NSError out-param.
    Either.catch { AVCaptureDeviceInput(device = device, error = null) }.getOrNull()

private fun configureSession(
    session: AVCaptureSession,
    delegate: FrameDelegate,
) {
    val device = authorizedVideoDevice() ?: return
    val input = createDeviceInput(device) ?: return

    session.beginConfiguration()
    session.sessionPreset = AVCaptureSessionPresetPhoto

    if (session.canAddInput(input)) {
        session.addInput(input)
    }

    // Dedicated serial queue for frame delivery — keeps Rust decode off the main thread.
    // Results are dispatched back to main via dispatch_async(dispatch_get_main_queue()).
    val analysisQueue: dispatch_queue_t = dispatch_queue_create("com.p2.apps.rustyqr.frame", null)

    val output =
        AVCaptureVideoDataOutput().apply {
            videoSettings =
                mapOf(
                    "PixelFormatType" to kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                )
            setSampleBufferDelegate(delegate, queue = analysisQueue)
        }

    if (session.canAddOutput(output)) {
        session.addOutput(output)
    }

    session.commitConfiguration()
}
