package com.p2.apps.rustyqr.bridge

import android.content.Intent
import androidx.core.content.FileProvider
import arrow.core.Either
import com.p2.apps.rustyqr.AppContextHolder
import java.io.File

// Must match the `${applicationId}.fileprovider` authority in this module's AndroidManifest.xml.
private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
private const val SHARE_CACHE_DIR = "qr_images"

actual suspend fun shareQrImage(
    bytes: ByteArray,
    suggestedName: String,
): Either<String, Unit> {
    val context = AppContextHolder.context
    return Either
        .catch {
            val dir = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
            val file = File(dir, "$suggestedName.png")
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
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
        }.mapLeft { it.message ?: "Unable to share QR code" }
}
