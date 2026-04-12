package com.p2.apps.rustyqr.bridge

import android.content.Intent
import android.net.Uri
import com.p2.apps.rustyqr.AppContextHolder

actual fun openUrl(url: String) {
    val context = AppContextHolder.context
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    context.startActivity(intent)
}
