package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import com.p2.apps.rustyqr.AppContextHolder
import java.io.File

private class AndroidFrameFiles(
    private val directory: File,
) : FrameFiles {
    override fun writeBytes(
        name: String,
        bytes: ByteArray,
    ): Either<String, Unit> = io { File(directory, name).writeBytes(bytes) }

    override fun appendLine(
        name: String,
        line: String,
    ): Either<String, Unit> = io { File(directory, name).appendText(line + "\n") }

    override fun listNames(): Either<String, List<String>> =
        io {
            val names = checkNotNull(directory.list()) { "cannot list $directory" }
            names.toList()
        }

    private fun <T> io(action: () -> T): Either<String, T> =
        Either
            .catch {
                check(directory.isDirectory || directory.mkdirs()) { "cannot create $directory" }
                action()
            }.mapLeft { it.message ?: "frame file access failed" }
}

actual fun frameFiles(): Either<String, FrameFiles> {
    val directory = File(AppContextHolder.context.cacheDir, "frames")
    return Either.Right(AndroidFrameFiles(directory))
}
