@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

actual suspend fun shareQrImage(
    bytes: ByteArray,
    suggestedName: String,
): Either<String, Unit> =
    withContext(Dispatchers.Main) {
        Either
            .catch { presentShareSheet(bytes) }
            .fold(
                ifLeft = { Either.Left(it.message ?: "Unable to share QR code") },
                ifRight = { it },
            )
    }

private fun presentShareSheet(bytes: ByteArray): Either<String, Unit> {
    val image = UIImage.imageWithData(bytes.toNSData())
    val presenter = topViewController()
    return when {
        image == null -> Either.Left("Invalid image data")
        presenter == null -> Either.Left("No view controller to present share sheet")
        else -> {
            val activityVC =
                UIActivityViewController(
                    activityItems = listOf(image),
                    applicationActivities = null,
                )
            presenter.presentViewController(activityVC, animated = true, completion = null)
            Either.Right(Unit)
        }
    }
}

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }

internal fun topViewController(): UIViewController? {
    val windows =
        UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
    val window: UIWindow? = windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull()
    var vc = window?.rootViewController
    while (vc?.presentedViewController != null) {
        vc = vc.presentedViewController
    }
    return vc
}
