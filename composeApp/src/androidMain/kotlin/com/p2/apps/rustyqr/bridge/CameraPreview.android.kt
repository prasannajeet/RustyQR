package com.p2.apps.rustyqr.bridge

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.p2.apps.rustyqr.camera.packLumaRows
import com.p2.apps.rustyqr.debug.FrameRecorder
import com.p2.apps.rustyqr.model.ScanResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android actual — CameraX [PreviewView] with [ImageAnalysis] for live QR decoding.
 *
 * Camera session stays alive when [isScanning] is false; only analysis is paused.
 * Frame analyzer: extracts Y-plane from [ImageProxy] → [QrBridge.decodeQrFromRaw].
 * [arrow.core.Either.Left] (no QR found) is silently discarded — expected for most frames.
 */
@Composable
actual fun CameraPreview(
    isScanning: Boolean,
    onQrDecoded: (ScanResult) -> Unit,
    onCameraReady: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val readyFired = remember { AtomicBoolean(false) }
    val scanningFlag = remember { AtomicBoolean(isScanning) }
    SideEffect { scanningFlag.set(isScanning) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                cameraProviderFuture.get().unbindAll()
            }, ContextCompat.getMainExecutor(context))
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val frameAnalysis =
                FrameAnalysis(
                    executor = analysisExecutor,
                    isScanning = { scanningFlag.get() },
                    onQrDecoded = onQrDecoded,
                    readyFired = readyFired,
                    onCameraReady = onCameraReady,
                )
            bindCamera(
                previewView = previewView,
                lifecycleOwner = lifecycleOwner,
                analysis = frameAnalysis,
            )
            previewView
        },
        update = { _ -> },
    )
}

/** Process-scoped so the debug frame budget is per app launch, not per composition. */
private val frameRecorder = FrameRecorder(platform = "android")

/** Everything the per-frame analyzer needs: where it runs and how it reports back. */
private class FrameAnalysis(
    val executor: java.util.concurrent.ExecutorService,
    val isScanning: () -> Boolean,
    val onQrDecoded: (ScanResult) -> Unit,
    val readyFired: AtomicBoolean,
    val onCameraReady: () -> Unit,
)

private fun bindCamera(
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    analysis: FrameAnalysis,
) {
    val context = previewView.context
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        val preview =
            Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        val imageAnalysis =
            ImageAnalysis
                .Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(analysis.executor) { imageProxy ->
                        // Fire the ready callback on the first delivered frame (even if not scanning),
                        // so the "Starting camera…" overlay clears as soon as the preview is live.
                        if (analysis.readyFired.compareAndSet(false, true)) {
                            ContextCompat.getMainExecutor(context).execute { analysis.onCameraReady() }
                        }
                        if (!analysis.isScanning()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        analyzeFrame(imageProxy, analysis, context)
                    }
                }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
        )
    }, ContextCompat.getMainExecutor(context))
}

/** Decodes one scanning frame, offers it to the debug [frameRecorder], and always closes [imageProxy]. */
private fun analyzeFrame(
    imageProxy: ImageProxy,
    analysis: FrameAnalysis,
    context: Context,
) {
    val planeProxy = imageProxy.planes[0]
    if (planeProxy.pixelStride == 1) {
        val buffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        packLumaRows(bytes, imageProxy.width, imageProxy.height, planeProxy.rowStride)
            .getOrNull()
            ?.let { packed ->
                val decoded = QrBridge.decodeQrFromRaw(packed, imageProxy.width, imageProxy.height)
                // The unpacked copy keeps the row padding exactly as delivered.
                frameRecorder.record(
                    luma = bytes,
                    width = imageProxy.width,
                    height = imageProxy.height,
                    rowStride = planeProxy.rowStride,
                    content = decoded.getOrNull()?.content,
                )
                // Left (no QR found) is silently discarded — expected for most frames
                decoded.onRight { result ->
                    ContextCompat.getMainExecutor(context).execute { analysis.onQrDecoded(result) }
                }
            }
    }
    imageProxy.close()
}
