@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
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
        try {
            val data = bytes.toNSData()
            val image = UIImage.imageWithData(data) ?: return@withContext Either.Left("Invalid image data")
            val presenter = topViewController() ?: return@withContext Either.Left("No view controller to present share sheet")
            val activityVC =
                UIActivityViewController(
                    activityItems = listOf(image),
                    applicationActivities = null,
                )
            presenter.presentViewController(activityVC, animated = true, completion = null)
            Either.Right(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Either.Left(t.message ?: "Unable to share QR code")
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

@Suppress("DEPRECATION")
internal fun topViewController(): UIViewController? {
    val app = UIApplication.sharedApplication
    val window: UIWindow? =
        app.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
            .firstOrNull { it.keyWindow }
            ?: app.keyWindow
    var vc = window?.rootViewController
    while (vc?.presentedViewController != null) {
        vc = vc.presentedViewController
    }
    return vc
}
