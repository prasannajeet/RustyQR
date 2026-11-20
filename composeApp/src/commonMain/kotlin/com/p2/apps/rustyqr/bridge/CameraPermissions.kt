package com.p2.apps.rustyqr.bridge

/**
 * The camera permission operations the Scan screen needs, as one seam.
 *
 * [PlatformCameraPermissions] is the production implementation and delegates to the platform bridge functions. The
 * seam lets ViewModel tests substitute a fake.
 */
interface CameraPermissions {
    /** Whether CAMERA permission is granted right now. */
    fun isGranted(): Boolean

    /** Whether the user denied the permission for good, so the system will not show its dialog again. */
    fun isPermanentlyDenied(): Boolean

    /** Asks for the permission; [onResult] receives whether it was granted, on the main thread. */
    fun request(onResult: (Boolean) -> Unit)

    /** Opens the app's system settings so the user can grant the permission there. */
    fun openSettings()
}

/** [CameraPermissions] backed by the platform bridge. */
object PlatformCameraPermissions : CameraPermissions {
    override fun isGranted(): Boolean = isCameraPermissionGranted()

    override fun isPermanentlyDenied(): Boolean = isCameraPermissionPermanentlyDenied()

    override fun request(onResult: (Boolean) -> Unit) = requestCameraPermission(onResult)

    override fun openSettings() = openAppSettings()
}
