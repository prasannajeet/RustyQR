package com.p2.apps.rustyqr.bridge

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import arrow.core.Either
import com.p2.apps.rustyqr.AppContextHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PICTURES_SUBDIR = "RustyQR"

actual suspend fun saveQrImage(
    bytes: ByteArray,
    suggestedName: String,
): Either<String, Unit> {
    val resolver = AppContextHolder.context.contentResolver
    return withContext(Dispatchers.IO) {
        try {
            val pending =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$suggestedName.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$PICTURES_SUBDIR",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
                    ?: error("MediaStore rejected the image")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Could not open gallery output stream")
            val finalize = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, finalize, null, null)
            Either.Right(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Either.Left(t.message ?: "Unable to save QR code")
        }
    }
}
