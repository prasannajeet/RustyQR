package com.p2.apps.rustyqr.bridge

import android.util.Log

actual fun logInfo(
    tag: String,
    message: String,
) {
    Log.i(tag, message)
}
