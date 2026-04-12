package com.p2.apps.rustyqr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            permissionCallback?.invoke(isGranted)
            permissionCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        MainActivityHolder.activity = this
        setContent {
            App()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (MainActivityHolder.activity === this) {
            MainActivityHolder.activity = null
        }
    }

    fun requestCameraPermission(onResult: (Boolean) -> Unit) {
        permissionCallback = onResult
        requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
