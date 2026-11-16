package com.p2.apps.rustyqr.bridge

import arrow.core.Either

/**
 * Writes debug camera-frame files into a `frames` directory in the platform's cache location
 * (Android: `cacheDir/frames`, iOS: `Caches/frames`).
 *
 * Calls are blocking file I/O. Failures are returned as [Either.Left] with a message, never thrown.
 */
interface FrameFiles {
    /** Writes [bytes] to the file [name], replacing any existing file. */
    fun writeBytes(
        name: String,
        bytes: ByteArray,
    ): Either<String, Unit>

    /** Appends [line] and a newline to the text file [name], creating it when missing. */
    fun appendLine(
        name: String,
        line: String,
    ): Either<String, Unit>
}

/** Creates the platform [FrameFiles]. Only call this when frame recording is enabled. */
expect fun frameFiles(): FrameFiles
