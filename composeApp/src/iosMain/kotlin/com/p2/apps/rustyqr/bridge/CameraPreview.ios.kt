@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.p2.apps.rustyqr.bridge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
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
        val sampleBuffer = didOutputSampleBuffer ?: return
        val pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) ?: return

        CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
        try {
            val baseAddress = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0u) ?: return
            val width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0u).toInt()
            val height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0u).toInt()
            val bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0u).toInt()

            if (width <= 0 || height <= 0) return

            // Row-by-row copy: bytesPerRow >= width due to row-stride padding.
            // Advance the pointer per row to avoid reading the entire buffer from index 0
            // on each iteration (which would be O(n²) in frame size).
            val dst = ByteArray(width * height)
            val bytePtr = baseAddress.reinterpret<ByteVar>()
            for (row in 0 until height) {
                val rowStart = (bytePtr + row.toLong() * bytesPerRow.toLong()) ?: continue
                rowStart.readBytes(width).copyInto(dst, destinationOffset = row * width)
            }

            QrBridge
                .decodeQrFromRaw(dst, width, height)
                .onRight { result ->
                    dispatch_async(dispatch_get_main_queue()) { onQrDecoded(result) }
                }
        } finally {
            CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
        }
    }
}

// ---------------------------------------------------------------------------
// Session configuration
// ---------------------------------------------------------------------------

private fun configureSession(
    session: AVCaptureSession,
    delegate: FrameDelegate,
) {
    val authStatus = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
    if (authStatus != AVAuthorizationStatusAuthorized) return

    val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return

    // AVCaptureDeviceInput throws a Kotlin exception when Objective-C sets the NSError out-param.
    val input =
        try {
            AVCaptureDeviceInput(device = device, error = null)
        } catch (e: Exception) {
            // The OS reported an error (e.g. device access denied after authorization check).
            // Session will have no input; abort configuration.
            return
        }

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
