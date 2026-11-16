package com.p2.apps.rustyqr.bridge

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS actual — opens [url] in the default browser via [UIApplication.openURL] (the options and completion overload).
 *
 * Dispatched to the main queue because UIKit mutations must run on the main thread.
 */
actual fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    dispatch_async(dispatch_get_main_queue()) {
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}
