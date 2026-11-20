package com.p2.apps.rustyqr.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.p2.apps.rustyqr.model.CameraFailure
import com.p2.apps.rustyqr.model.ScanResult

/**
 * Remembers a [FrameDispatcher] that reads the latest arguments and is closed when it leaves composition.
 *
 * Closing happens in `onDispose`, which runs on the main thread during apply. That is before any deferred view
 * release (Compose Multiplatform's `UIKitView.onRelease` runs a frame later), so nothing fires after the preview
 * has left composition.
 *
 * @param isScanning Latest scan gate, read on the analysis thread for every frame.
 * @param onQrDecoded Latest decode callback, invoked on the main thread.
 * @param onCameraReady Latest ready callback, invoked at most once on the main thread.
 * @param onCameraError Latest failure callback, invoked at most once on the main thread.
 * @param postToMain Runs the given work on the main thread.
 */
@Composable
internal fun rememberFrameDispatcher(
    isScanning: Boolean,
    onQrDecoded: (ScanResult) -> Unit,
    onCameraReady: () -> Unit,
    onCameraError: (CameraFailure) -> Unit,
    postToMain: (() -> Unit) -> Unit,
): FrameDispatcher {
    val latestScanning by rememberUpdatedState(isScanning)
    val latestOnDecoded by rememberUpdatedState(onQrDecoded)
    val latestOnReady by rememberUpdatedState(onCameraReady)
    val latestOnError by rememberUpdatedState(onCameraError)
    // Each callback must be a lambda that reads the State at call time. Passing a value (`onQrDecoded =
    // latestOnDecoded`) or a reference (`latestOnDecoded::invoke`) would capture the first callback for the life of
    // the camera, because this block runs once.
    val dispatcher =
        remember {
            FrameDispatcher(
                isScanning = { latestScanning },
                onQrDecoded = { latestOnDecoded(it) },
                onCameraReady = { latestOnReady() },
                onCameraError = { latestOnError(it) },
                postToMain = postToMain,
            )
        }
    DisposableEffect(dispatcher) { onDispose { dispatcher.close() } }
    return dispatcher
}
