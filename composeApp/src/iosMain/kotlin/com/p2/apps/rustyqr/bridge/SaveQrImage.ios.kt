@file:OptIn(ExperimentalForeignApi::class)

package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume

actual suspend fun saveQrImage(
    bytes: ByteArray,
    suggestedName: String,
): Either<String, Unit> =
    suspendCancellableCoroutine { cont ->
        val data = bytes.toNSData()
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
            if (status != PHAuthorizationStatusAuthorized && status != PHAuthorizationStatusLimited) {
                if (cont.isActive) cont.resume(Either.Left("Photo library permission denied"))
                return@requestAuthorizationForAccessLevel
            }
            PHPhotoLibrary.sharedPhotoLibrary().performChanges({
                val request = PHAssetCreationRequest.creationRequestForAsset()
                request.addResourceWithType(
                    type = PHAssetResourceTypePhoto,
                    data = data,
                    options = null,
                )
            }, completionHandler = { success, error ->
                if (!cont.isActive) return@performChanges
                if (success) {
                    cont.resume(Either.Right(Unit))
                } else {
                    cont.resume(Either.Left(error?.localizedDescription ?: "Unable to save QR code"))
                }
            })
        }
    }
