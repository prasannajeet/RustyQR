package com.p2.apps.rustyqr.bridge

// Camera permission bridge — cross-platform interface for querying and requesting camera access.

/** Returns true if CAMERA permission is currently granted. */
expect fun isCameraPermissionGranted(): Boolean

/**
 * Requests CAMERA permission from the user.
 *
 * @param onResult Callback invoked on the main thread with [true] if permission was granted,
 *                 [false] if denied.
 */
expect fun requestCameraPermission(onResult: (Boolean) -> Unit)

/**
 * Returns true if the user has permanently denied camera permission
 * (i.e., "Don't ask again" was selected on Android).
 *
 * On iOS Phase 7, this maps to AVAuthorizationStatus.denied.
 */
expect fun isCameraPermissionPermanentlyDenied(): Boolean

/**
 * Opens the platform app settings so the user can manually grant camera permission.
 */
expect fun openAppSettings()
