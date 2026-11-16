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
    ): Either<String, Unit> = write { File(directory, name).writeBytes(bytes) }

    override fun appendLine(
        name: String,
        line: String,
    ): Either<String, Unit> = write { File(directory, name).appendText(line + "\n") }

    private fun write(action: () -> Unit): Either<String, Unit> =
        Either
            .catch {
                check(directory.isDirectory || directory.mkdirs()) { "cannot create $directory" }
                action()
            }.mapLeft { it.message ?: "frame write failed" }
}

actual fun frameFiles(): FrameFiles = AndroidFrameFiles(File(AppContextHolder.context.cacheDir, "frames"))
