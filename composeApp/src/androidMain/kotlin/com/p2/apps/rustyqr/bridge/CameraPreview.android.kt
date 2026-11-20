package com.p2.apps.rustyqr.bridge

import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
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
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import com.p2.apps.rustyqr.camera.FrameDispatcher
import com.p2.apps.rustyqr.camera.packLumaRows
import com.p2.apps.rustyqr.camera.rememberFrameDispatcher
import com.p2.apps.rustyqr.debug.FrameRecorder
import com.p2.apps.rustyqr.debug.FrameTimings
import com.p2.apps.rustyqr.debug.LOG_FRAME_TIMINGS
import com.p2.apps.rustyqr.model.CameraFailure
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
 * The camera outlives recompositions, so [rememberFrameDispatcher] reads [isScanning], [onQrDecoded],
 * [onCameraReady] and [onCameraError] at the moment they are used (the latest value always wins) and closes
 * itself on leaving composition.
 */
@Composable
actual fun CameraPreview(
    isScanning: Boolean,
    onQrDecoded: (ScanResult) -> Unit,
    onCameraReady: () -> Unit,
    onCameraError: (CameraFailure) -> Unit,
    modifier: Modifier,
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
            onCameraError = onCameraError,
            postToMain = { work -> mainExecutor.execute(work) },
        )
    val session = remember { CameraSession(dispatcher, mainExecutor) }

    DisposableEffect(session) {
        onDispose { session.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            session.bind(previewView, lifecycleOwner)
            previewView
        },
        update = { _ -> },
    )
}

private const val TAG = "CameraPreview"

/** Process-scoped so the debug frame budget is per app launch, not per composition. */
private val frameRecorder = FrameRecorder(platform = "android")

/**
 * One camera binding: the analysis thread, the use cases and the analyzer feeding the [FrameDispatcher].
 * Main-thread only. Holds no Activity or [PreviewView] after [release]. The dispatcher is closed by
 * [rememberFrameDispatcher], not here.
 */
private class CameraSession(
    private val dispatcher: FrameDispatcher,
    private val mainExecutor: Executor,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val analyzer = FrameAnalyzer(dispatcher)
    private var released = false
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraState: LiveData<CameraState>? = null
    private var staleCameraState: CameraState? = null
    private val cameraStateObserver = Observer<CameraState> { state -> onCameraState(state) }

    fun bind(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
    ) {
        val future = ProcessCameraProvider.getInstance(previewView.context)
        future.addListener({
            // The provider future can complete after the composable has left; binding then would leak a camera.
            if (!released) start(future::get, previewView, lifecycleOwner)
        }, mainExecutor)
    }

    /**
     * Binds the use cases, or reports why not. CameraX throws when it cannot initialise (the future fails), when the
     * device has no back camera (IllegalArgumentException) and in other documented cases (IllegalStateException), so
     * every throw becomes [CameraFailure.CouldNotStart]; a missing back camera is detected up front as
     * [CameraFailure.NoCamera]. On failure nothing stays bound.
     */
    private fun start(
        providerResult: () -> ProcessCameraProvider,
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
    ) {
        Either
            .catch { bindUseCases(providerResult(), previewView, lifecycleOwner) }
            .getOrElse { error ->
                logInfo(TAG, "Camera failed to start: $error")
                CameraFailure.CouldNotStart.left()
            }.onLeft { failure ->
                unbindUseCases()
                dispatcher.fail(failure)
            }
    }

    private fun bindUseCases(
        cameraProvider: ProcessCameraProvider,
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
    ): Either<CameraFailure, Unit> {
        if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) return CameraFailure.NoCamera.left()
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
        val camera =
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                newPreview,
                newAnalysis,
            )
        // CameraX keeps one LiveData per camera for the whole process, and observing while STARTED delivers its current
        // value at once. After a retry that is still the previous session's CLOSED + CRITICAL error, so remember it and
        // ignore exactly that object; CameraX publishes a new object for every real change. The field is assigned
        // before observing so a synchronous callback can unbind and remove the observer.
        val liveState = camera.cameraInfo.cameraState
        staleCameraState = liveState.value
        cameraState = liveState
        liveState.observe(lifecycleOwner, cameraStateObserver)
        return Unit.right()
    }

    /**
     * Reports critical camera-state errors that arrive after binding (the loader would otherwise spin forever).
     * Recoverable ones are left alone because CameraX retries them. Observed on the main thread.
     */
    private fun onCameraState(state: CameraState) {
        if (state === staleCameraState) return
        state.error?.takeIf { it.type == CameraState.ErrorType.CRITICAL }?.let { error ->
            logInfo(TAG, "Camera reported a critical error, code=${error.code}, cause=${error.cause}")
            unbindUseCases()
            dispatcher.fail(CameraFailure.CouldNotStart)
        }
    }

    /**
     * Unbinds only this session's use cases, never other users of the shared provider, and drops the surface and the
     * camera-state observer.
     */
    private fun unbindUseCases() {
        cameraState?.removeObserver(cameraStateObserver)
        cameraState = null
        staleCameraState = null
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
