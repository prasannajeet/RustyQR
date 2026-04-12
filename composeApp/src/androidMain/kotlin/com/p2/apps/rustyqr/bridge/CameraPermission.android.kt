package com.p2.apps.rustyqr.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.p2.apps.rustyqr.MainActivityHolder

/**
 * Android actual — delegates to [MainActivityHolder] for context access.
 *
 * [isCameraPermissionGranted] is safe to call from any thread.
 * [requestCameraPermission] must be called from the main thread.
 */
actual fun isCameraPermissionGranted(): Boolean {
    val context = MainActivityHolder.activity ?: return false
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
}

actual fun requestCameraPermission(onResult: (Boolean) -> Unit) {
    val activity =
        MainActivityHolder.activity ?: run {
            onResult(false)
            return
        }
    activity.requestCameraPermission(onResult)
}

actual fun isCameraPermissionPermanentlyDenied(): Boolean {
    val activity = MainActivityHolder.activity ?: return false
    // Not granted AND shouldShowRationale is false → permanently denied
    val notGranted =
        ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA,
        ) != PackageManager.PERMISSION_GRANTED
    val shouldShow =
        ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.CAMERA,
        )
    return notGranted && !shouldShow
}

actual fun openAppSettings() {
    val activity = MainActivityHolder.activity ?: return
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
    activity.startActivity(intent)
}
