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
 * Bridge to the Rust QR library, shared by Android and iOS.
 *
 * Delegates to the UniFFI-generated bindings in the `:rustyqr-bindings` module.
 *
 * All functions are synchronous and MUST be called from [kotlinx.coroutines.Dispatchers.IO],
 * never the main thread, because each call crosses into native code.
 *
 * Error contract: functions return [Either.Left] with a [QrError] on domain errors.
 * Callers use [Either.fold] or [Either.onRight] — no try/catch required.
 */
object QrBridge {
    /**
     * Generates a QR code PNG for the given text content at the specified pixel size.
     *
     * @param content The text or URL to encode. Must not be blank.
     * @param size Output image side length in pixels (1..4096).
     * @return [Either.Right] with PNG bytes, or [Either.Left] with [QrError] on failure.
     */
    fun generateQrPng(
        content: String,
        size: Int,
    ): Either<QrError, ByteArray> =
        Either
            .catch { ffiGeneratePng(content, size.toUInt()) }
            .mapLeft { ffiErrorToQrError(it) }

    /**
     * Decodes a QR code from PNG or JPEG image bytes.
     *
     * @param imageData Raw PNG or JPEG bytes.
     * @return [Either.Right] with [ScanResult], or [Either.Left] with [QrError] on failure.
     */
    fun decodeQr(imageData: ByteArray): Either<QrError, ScanResult> =
        Either
            .catch { ScanResult(content = ffiDecodeQr(imageData).content) }
            .mapLeft { ffiErrorToQrError(it) }

    /**
     * Decodes a QR code from a raw grayscale (Y-plane) pixel buffer.
     *
     * This is the camera-frame path — avoids PNG round-trip overhead.
     * Most camera frames will return [Either.Left] (no QR found); callers
     * should use [Either.onRight] and silently discard Left values.
     *
     * @param pixels Raw luma bytes (the Y plane of a camera frame).
     * @param width  Frame width in pixels.
     * @param height Frame height in pixels.
     * @return [Either.Right] with [ScanResult], or [Either.Left] with [QrError] on failure.
     */
    fun decodeQrFromRaw(
        pixels: ByteArray,
        width: Int,
        height: Int,
    ): Either<QrError, ScanResult> =
        Either
            .catch { ScanResult(content = ffiDecodeQrFromRaw(pixels, width.toUInt(), height.toUInt()).content) }
            .mapLeft { ffiErrorToQrError(it) }

    /**
     * Returns the Rust library version string (from [rusty_qr_core] Cargo.toml).
     */
    fun getLibraryVersion(): String = ffiGetLibraryVersion()
}

/**
 * Maps [FfiQrException] variants to the corresponding [QrError] subtype.
 * Falls back to [QrError.from] for any other throwable.
 */
private fun ffiErrorToQrError(throwable: Throwable): QrError =
    when (throwable) {
        is FfiQrException.InvalidInput -> QrError.InvalidInput(throwable.message ?: "Invalid input")
        is FfiQrException.EncodingFailed -> QrError.EncodingFailed(throwable.message ?: "Encoding failed")
        is FfiQrException.DecodingFailed -> QrError.DecodingFailed(throwable.message ?: "Decoding failed")
        is FfiQrException.ImageException -> QrError.ImageError(throwable.message ?: "Image error")
        else -> QrError.from(throwable)
    }
