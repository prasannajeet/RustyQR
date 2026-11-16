package com.p2.apps.rustyqr.bridge

/**
 * Writes an informational log line. [tag] groups related lines; [message] is logged verbatim, so it may contain
 * any characters, including `%`.
 */
expect fun logInfo(
    tag: String,
    message: String,
)
