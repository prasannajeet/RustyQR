package com.p2.apps.rustyqr.bridge

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.p2.apps.rustyqr.camera.FrameDispatcher
import com.p2.apps.rustyqr.camera.packLumaRows
import com.p2.apps.rustyqr.camera.rememberFrameDispatcher
import com.p2.apps.rustyqr.debug.FrameRecorder
import com.p2.apps.rustyqr.debug.FrameTimings
import com.p2.apps.rustyqr.debug.LOG_FRAME_TIMINGS
import com.p2.apps.rustyqr.model.ScanResult
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Android actual — CameraX [PreviewView] with [ImageAnalysis] for live QR decoding.
 *
 * Camera session stays alive when [isScanning] is false; only analysis is paused.
 * Frame analyzer: extracts Y-plane from [ImageProxy] → [QrBridge.decodeQrFromRaw].
 * [arrow.core.Either.Left] (no QR found) is silently discarded — expected for most frames.
 *
 * The camera outlives recompositions, so [rememberFrameDispatcher] reads [isScanning], [onQrDecoded] and
 * [onCameraReady] at the moment they are used (the latest value always wins) and closes itself on leaving composition.
 */
@Composable
actual fun CameraPreview(
    isScanning: Boolean,
    onQrDecoded: (ScanResult) -> Unit,
    onCameraReady: () -> Unit,
) {
    // Assumed stable: it is the Activity (Scan has no NavHost), and the AndroidView factory below binds only once.
    // Keying the session on it would not rebind, because the factory does not rerun, so it is deliberately not keyed.
    val lifecycleOwner = LocalLifecycleOwner.current
    // Application context: nothing long-lived below may hold the Activity.
    val appContext = LocalContext.current.applicationContext
    // Not keyed: the dispatcher and session below keep the first executor, so a keyed remember would only mislead.
    val mainExecutor = remember { ContextCompat.getMainExecutor(appContext) }
    val dispatcher =
        rememberFrameDispatcher(
            isScanning = isScanning,
            onQrDecoded = onQrDecoded,
            onCameraReady = onCameraReady,
            postToMain = { work -> mainExecutor.execute(work) },
        )
    val session = remember { CameraSession(dispatcher, mainExecutor) }

    DisposableEffect(session) {
        onDispose { session.release() }
    }

    AndroidView(
        modifier = Modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            session.bind(previewView, lifecycleOwner)
            previewView
        },
        update = { _ -> },
    )
}

/** Process-scoped so the debug frame budget is per app launch, not per composition. */
private val frameRecorder = FrameRecorder(platform = "android")

/**
 * One camera binding: the analysis thread, the use cases and the analyzer feeding the [FrameDispatcher].
 * Main-thread only. Holds no Activity or [PreviewView] after [release]. The dispatcher is closed by
 * [rememberFrameDispatcher], not here.
 */
private class CameraSession(
    dispatcher: FrameDispatcher,
    private val mainExecutor: Executor,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val analyzer = FrameAnalyzer(dispatcher)
    private var released = false
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null

    fun bind(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
    ) {
        val future = ProcessCameraProvider.getInstance(previewView.context)
        future.addListener({
            // The provider future can complete after the composable has left; binding then would leak a camera.
            if (!released) bindUseCases(future.get(), previewView, lifecycleOwner)
        }, mainExecutor)
    }

    private fun bindUseCases(
        cameraProvider: ProcessCameraProvider,
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
    ) {
        unbindUseCases()
        val newPreview =
            Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
        val newAnalysis =
            ImageAnalysis
                .Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase -> useCase.setAnalyzer(executor, analyzer::analyze) }
        provider = cameraProvider
        preview = newPreview
        imageAnalysis = newAnalysis
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            newPreview,
            newAnalysis,
        )
    }

    /** Unbinds only this session's use cases, never other users of the shared provider, and drops the surface. */
    private fun unbindUseCases() {
        imageAnalysis?.let { useCase ->
            useCase.clearAnalyzer()
            provider?.unbind(useCase)
        }
        preview?.let { useCase ->
            provider?.unbind(useCase)
            useCase.surfaceProvider = null
        }
        imageAnalysis = null
        preview = null
        provider = null
    }

    /** Unbinds this session's use cases and shuts the analysis thread down. */
    fun release() {
        released = true
        unbindUseCases()
        executor.shutdown()
    }
}

/** Per-frame work. Runs only on the session's single analysis thread, so [timings] needs no locking. */
private class FrameAnalyzer(
    private val dispatcher: FrameDispatcher,
) {
    private val timings = FrameTimings()

    fun analyze(imageProxy: ImageProxy) {
        // Timed around the whole analyzer, close included. Frames that never reach Rust stay uncounted.
        if (LOG_FRAME_TIMINGS) timings.startFrame()
        imageProxy.use { analyzeFrame(it) }
        if (LOG_FRAME_TIMINGS) timings.endFrame()
    }

    /** Analyzes one frame; the caller closes [proxy]. Does nothing past the ready callback while not scanning. */
    private fun analyzeFrame(proxy: ImageProxy) {
        if (!dispatcher.onFrame()) return
        val plane = proxy.planes[0]
        if (plane.pixelStride == 1) decodePlane(proxy, plane)
    }

    /** Decodes the Y plane, then offers it to the debug [frameRecorder]. */
    private fun decodePlane(
        proxy: ImageProxy,
        plane: ImageProxy.PlaneProxy,
    ) {
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val packed = packLumaRows(bytes, proxy.width, proxy.height, plane.rowStride).getOrNull() ?: return
        if (LOG_FRAME_TIMINGS) timings.endCopy()
        val decoded = QrBridge.decodeQrFromRaw(packed, proxy.width, proxy.height)
        if (LOG_FRAME_TIMINGS) timings.endRust(decoded)
        // Left (no QR found) is silently discarded — expected for most frames
        decoded.onRight(dispatcher::deliver)
        // After the result is dispatched, so the blocking write cannot delay it. `bytes` keeps the row padding.
        frameRecorder.record(bytes, proxy.width, proxy.height, plane.rowStride, decoded.getOrNull()?.content)
    }
}
