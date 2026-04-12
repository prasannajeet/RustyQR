package com.p2.apps.rustyqr.bridge

/**
 * Opens a URL in the platform's default browser / URL handler.
 *
 * @param url A fully qualified URL (must start with http:// or https://).
 */
expect fun openUrl(url: String)
