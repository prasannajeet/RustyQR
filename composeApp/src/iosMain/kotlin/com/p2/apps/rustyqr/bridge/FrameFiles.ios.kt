@file:OptIn(ExperimentalForeignApi::class)

package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import arrow.core.flatMap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

private class IosFrameFiles(
    private val directory: String,
) : FrameFiles {
    override fun writeBytes(
        name: String,
        bytes: ByteArray,
    ): Either<String, Unit> = ensureDirectory().flatMap { write("$directory/$name", "wb", bytes) }

    override fun appendLine(
        name: String,
        line: String,
    ): Either<String, Unit> {
        val bytes = (line + "\n").encodeToByteArray()
        return ensureDirectory().flatMap { write("$directory/$name", "ab", bytes) }
    }

    override fun listNames(): Either<String, List<String>> =
        if (NSFileManager.defaultManager.fileExistsAtPath(directory)) {
            val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)
            names?.filterIsInstance<String>()?.let { Either.Right(it) } ?: Either.Left("cannot list $directory")
        } else {
            Either.Right(emptyList())
        }

    override fun readLines(name: String): Either<String, List<String>> {
        val path = "$directory/$name"
        return if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            NSData.dataWithContentsOfFile(path)?.let { Either.Right(linesOf(it)) } ?: Either.Left("cannot read $path")
        } else {
            Either.Right(emptyList())
        }
    }

    // Lossy like Android's readLines: a torn last write must not stop the frame numbers being seeded.
    private fun linesOf(data: NSData): List<String> {
        val text = (data.bytes?.readBytes(data.length.toInt()) ?: ByteArray(0)).decodeToString()
        // A trailing newline does not add an empty last line.
        val lines = text.lines()
        return if (lines.lastOrNull() == "") lines.dropLast(1) else lines
    }

    private fun ensureDirectory(): Either<String, Unit> =
        if (NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) {
            Either.Right(Unit)
        } else {
            Either.Left("cannot create $directory")
        }

    private fun write(
        path: String,
        mode: String,
        bytes: ByteArray,
    ): Either<String, Unit> {
        val file = fopen(path, mode)
        val written =
            when {
                file == null -> -1L
                bytes.isEmpty() -> 0L
                else -> bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file).toLong() }
            }
        // fclose can surface a deferred write error such as ENOSPC, so its result decides success too.
        val closed = file != null && fclose(file) == 0
        return if (closed && written == bytes.size.toLong()) Either.Right(Unit) else Either.Left("cannot write $path")
    }
}

actual fun frameFiles(): Either<String, FrameFiles> {
    val caches =
        NSFileManager.defaultManager
            .URLForDirectory(NSCachesDirectory, NSUserDomainMask, null, true, null)
            ?.path
    return if (caches != null) {
        Either.Right(IosFrameFiles("$caches/frames"))
    } else {
        Either.Left("caches directory unavailable")
    }
}
