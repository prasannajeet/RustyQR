package com.p2.apps.rustyqr.bridge

import android.content.Intent
import androidx.core.content.FileProvider
import arrow.core.Either
import com.p2.apps.rustyqr.AppContextHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val FILE_PROVIDER_AUTHORITY = "com.p2.apps.rustyqr.fileprovider"
private const val SHARE_CACHE_DIR = "qr_images"

actual suspend fun shareQrImage(
    bytes: ByteArray,
    suggestedName: String,
): Either<String, Unit> {
    val context = AppContextHolder.context
    return withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
            val file = File(dir, "$suggestedName.png")
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            val chooser =
                Intent.createChooser(send, "Share QR code").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(chooser)
            Either.Right(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Either.Left(t.message ?: "Unable to share QR code")
        }
    }
}
