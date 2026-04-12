package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import com.p2.apps.rustyqr.model.QrError
import com.p2.apps.rustyqr.model.ScanResult
import com.p2.apps.rustyqr.rust.FfiQrException
import com.p2.apps.rustyqr.rust.decodeQr as ffiDecodeQr
import com.p2.apps.rustyqr.rust.decodeQrFromRaw as ffiDecodeQrFromRaw
import com.p2.apps.rustyqr.rust.generatePng as ffiGeneratePng
import com.p2.apps.rustyqr.rust.getLibraryVersion as ffiGetLibraryVersion

/**
 * Android actual — delegates to UniFFI-generated Kotlin bindings.
 *
 * Type conversions:
 *   - [ByteArray] output: UniFFI returns [ByteArray] directly for byte functions
 *   - [Int] size → [UInt] for Rust `u32` parameter
 *   - [FfiScanResult] → [ScanResult]
 *   - [FfiQrException] → [QrError] via [ffiErrorToQrError]
 */
actual object QrBridge {
    actual fun generateQrPng(
        content: String,
        size: Int,
    ): Either<QrError, ByteArray> =
        Either
            .catch { ffiGeneratePng(content, size.toUInt()) }
            .mapLeft { ffiErrorToQrError(it) }

    actual fun decodeQr(imageData: ByteArray): Either<QrError, ScanResult> =
        Either
            .catch { ScanResult(content = ffiDecodeQr(imageData).content) }
            .mapLeft { ffiErrorToQrError(it) }

    actual fun decodeQrFromRaw(
        pixels: ByteArray,
        width: Int,
        height: Int,
    ): Either<QrError, ScanResult> =
        Either
            .catch { ScanResult(content = ffiDecodeQrFromRaw(pixels, width.toUInt(), height.toUInt()).content) }
            .mapLeft { ffiErrorToQrError(it) }

    actual fun getLibraryVersion(): String = ffiGetLibraryVersion()
}

/**
 * Maps [FfiQrException] variants to the corresponding [QrError] subtype.
 * Falls back to [QrError.DecodingFailed] for any non-FFI throwable.
 */
private fun ffiErrorToQrError(throwable: Throwable): QrError =
    when (throwable) {
        is FfiQrException.InvalidInput -> QrError.InvalidInput(throwable.message ?: "Invalid input")
        is FfiQrException.EncodingFailed -> QrError.EncodingFailed(throwable.message ?: "Encoding failed")
        is FfiQrException.DecodingFailed -> QrError.DecodingFailed(throwable.message ?: "Decoding failed")
        is FfiQrException.ImageException -> QrError.ImageError(throwable.message ?: "Image error")
        else -> QrError.from(throwable)
    }
