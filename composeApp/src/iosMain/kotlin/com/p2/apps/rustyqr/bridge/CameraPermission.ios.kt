@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.p2.apps.rustyqr.bridge

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS actual — delegates to [AVCaptureDevice] for camera permission checks and requests.
 *
 * Both [authorizationStatusForMediaType] and [requestAccessForMediaType] are
 * Obj-C class methods mapped by Kotlin/Native as extension functions on [AVCaptureDevice]'s
 * companion object, so they must be imported explicitly.
 */
actual fun isCameraPermissionGranted(): Boolean = cameraAuthorizationStatus() == AVAuthorizationStatusAuthorized

/**
 * iOS actual — requests camera access.
 * The callback is always delivered on the main queue.
 */
actual fun requestCameraPermission(onResult: (Boolean) -> Unit) {
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
        dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
    }
}

/**
 * iOS actual — returns true when camera access is denied by the user or restricted (Screen Time, MDM). Neither
 * shows a prompt, so only the Settings path can help.
 */
actual fun isCameraPermissionPermanentlyDenied(): Boolean = isPermanentlyDenied(cameraAuthorizationStatus())

internal fun isPermanentlyDenied(status: Long): Boolean =
    when (status) {
        AVAuthorizationStatusDenied, AVAuthorizationStatusRestricted -> true
        else -> false
    }

/**
 * iOS actual — opens the app's settings page so the user can change camera access.
 */
actual fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    dispatch_async(dispatch_get_main_queue()) {
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}

private fun cameraAuthorizationStatus() = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
