package com.p2.apps.rustyqr.bridge

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
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
            bindCamera(
                context = ctx,
                previewView = previewView,
                lifecycleOwner = lifecycleOwner,
                analysisExecutor = analysisExecutor,
                isScanning = { isScanning },
                onQrDecoded = onQrDecoded,
                readyFired = readyFired,
                onCameraReady = onCameraReady,
            )
            previewView
        },
        update = { _ ->
            // isScanning changes are read directly in the analyzer lambda
        },
    )
}

private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    analysisExecutor: java.util.concurrent.ExecutorService,
    isScanning: () -> Boolean,
    onQrDecoded: (ScanResult) -> Unit,
    readyFired: AtomicBoolean,
    onCameraReady: () -> Unit,
) {
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
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        // Fire the ready callback on the first delivered frame (even if not scanning),
                        // so the "Starting camera…" overlay clears as soon as the preview is live.
                        if (readyFired.compareAndSet(false, true)) {
                            ContextCompat.getMainExecutor(context).execute { onCameraReady() }
                        }
                        if (!isScanning()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val planeProxy = imageProxy.planes[0]
                        val buffer = planeProxy.buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        QrBridge
                            .decodeQrFromRaw(
                                pixels = bytes,
                                width = imageProxy.width,
                                height = imageProxy.height,
                            ).onRight { result ->
                                // Left (no QR found) is silently discarded — expected for most frames
                                val mainExecutor = ContextCompat.getMainExecutor(context)
                                mainExecutor.execute { onQrDecoded(result) }
                            }
                        imageProxy.close()
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
