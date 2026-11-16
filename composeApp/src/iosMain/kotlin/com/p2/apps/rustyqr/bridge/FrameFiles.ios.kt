@file:OptIn(ExperimentalForeignApi::class)

package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

private class IosFrameFiles(
    private val directory: String,
) : FrameFiles {
    override fun writeBytes(
        name: String,
        bytes: ByteArray,
    ): Either<String, Unit> = write("$directory/$name", "wb", bytes)

    override fun appendLine(
        name: String,
        line: String,
    ): Either<String, Unit> = write("$directory/$name", "ab", (line + "\n").encodeToByteArray())

    private fun write(
        path: String,
        mode: String,
        bytes: ByteArray,
    ): Either<String, Unit> {
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        val file = fopen(path, mode)
        val written =
            when {
                file == null -> -1L
                bytes.isEmpty() -> 0L
                else -> bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file).toLong() }
            }
        if (file != null) fclose(file)
        return if (written == bytes.size.toLong()) Either.Right(Unit) else Either.Left("cannot write $path")
    }
}

actual fun frameFiles(): FrameFiles {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull()
    return IosFrameFiles("${caches as? String ?: "."}/frames")
}
