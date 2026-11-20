package com.p2.apps.rustyqr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Activity contract the platform bridges need: registered in [BridgeHostActivityHolder] for its lifetime and owner of
 * the CAMERA permission launcher. The app module's launcher Activity extends this class.
 *
 * The callbacks waiting for the permission result live in [PendingPermissionRequest], not here, so a result that
 * arrives after a rotation still reaches them.
 */
open class BridgeHostActivity : ComponentActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            PendingPermissionRequest.complete(isGranted)
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
        // A rotation keeps the request alive for the next Activity; leaving for good means no result will arrive.
        if (isFinishing) {
            PendingPermissionRequest.cancel()
        }
    }

    /**
     * Asks for the CAMERA permission and reports the outcome to [onResult]. A request made while the dialog is
     * already open shares its result instead of opening a second dialog.
     */
    internal fun requestCameraPermission(onResult: (Boolean) -> Unit) {
        if (PendingPermissionRequest.add(onResult)) {
            requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }
}
