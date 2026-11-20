package com.p2.apps.rustyqr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Activity contract the platform bridges need: registered in [BridgeHostActivityHolder] for its lifetime and owner of
 * the CAMERA permission launcher. The app module's launcher Activity extends this class.
 */
open class BridgeHostActivity : ComponentActivity() {
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            permissionCallback?.invoke(isGranted)
            permissionCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeHostActivityHolder.activity = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BridgeHostActivityHolder.activity === this) {
            BridgeHostActivityHolder.activity = null
        }
    }

    /** Asks for the CAMERA permission and reports the outcome to [onResult]. */
    internal fun requestCameraPermission(onResult: (Boolean) -> Unit) {
        permissionCallback = onResult
        requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }
}
