package com.p2.apps.rustyqr.bridge

import platform.Foundation.NSLog

actual fun logInfo(
    tag: String,
    message: String,
) {
    // The message is an argument, never the format string, so a '%' in it cannot be misread as a specifier.
    NSLog("%@", "$tag: $message")
}
