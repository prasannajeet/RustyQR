package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import com.p2.apps.rustyqr.model.QrError
import com.p2.apps.rustyqr.model.ScanResult
import com.p2.apps.rustyqr.rust.FfiQrException
import com.p2.apps.rustyqr.rust.contentFits as ffiContentFits
import com.p2.apps.rustyqr.rust.decodeQr as ffiDecodeQr
import com.p2.apps.rustyqr.rust.decodeQrFromRaw as ffiDecodeQrFromRaw
import com.p2.apps.rustyqr.rust.generatePng as ffiGeneratePng

/**
 * Bridge to the Rust QR library, shared by Android and iOS.
 *
 * Delegates to the UniFFI-generated bindings in the `:rustyqr-bindings` module.
 *
 * All functions are synchronous and blocking, because each call crosses into native code, so call
 * them off the main thread. The caller chooses the context, for example a ViewModel's injected
 * `ioDispatcher`, or the camera analysis executor or queue.
 *
 * Error contract: [generateQrPng], [contentFits], [decodeQr] and [decodeQrFromRaw] return [Either.Left] with a
 * [QrError] on failure, so callers use [Either.fold] or [Either.onRight] and need no try/catch.
 */
object QrBridge : QrEncoder {
    /**
     * Generates a QR code PNG for the given text content at the specified pixel size.
     *
     * @param content The text or URL to encode. Must not be blank.
     * @param size Output image side length in pixels (at most 4096). Rust draws whole pixels per module, so a size
     *   smaller than the code's module count plus its quiet zone returns [QrError.InvalidInput]. Any size of at least
     *   185 px (version 40) holds every code that [contentFits] accepts.
     * @return [Either.Right] with PNG bytes, or [Either.Left] with [QrError] on failure.
     */
    override fun generateQrPng(
        content: String,
        size: Int,
    ): Either<QrError, ByteArray> =
        Either
            .catch { ffiGeneratePng(content, size.toUInt()) }
            .mapLeft { encodeError(it) }

    /**
     * Reports whether [content] fits in a QR code, as decided by the same Rust encoder that generates it.
     *
     * @param content The text or URL to check. Must not be empty.
     * @return [Either.Right] with whether it fits, or [Either.Left] with [QrError.InvalidInput] for empty content, or another [QrError]
     *   if the check itself fails.
     */
    override fun contentFits(content: String): Either<QrError, Boolean> =
        Either
            .catch { ffiContentFits(content) }
            .mapLeft { encodeError(it) }

    /**
     * Decodes a QR code from PNG or JPEG image bytes.
     *
     * @param imageData Raw PNG or JPEG bytes.
     * @return [Either.Right] with [ScanResult], or [Either.Left] with [QrError] on failure.
     */
    fun decodeQr(imageData: ByteArray): Either<QrError, ScanResult> =
        Either
            .catch { ScanResult(content = ffiDecodeQr(imageData).content) }
            .mapLeft { decodeError(it) }

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
            .mapLeft { decodeError(it) }
}

/** The [QrError] for a throwable from an encode call: a non-FFI failure there is an [QrError.EncodingFailed]. */
internal fun encodeError(throwable: Throwable): QrError = ffiErrorToQrError(throwable, QrError::EncodingFailed)

/** The [QrError] for a throwable from a decode call: a non-FFI failure there is a [QrError.DecodingFailed]. */
internal fun decodeError(throwable: Throwable): QrError = ffiErrorToQrError(throwable, QrError::DecodingFailed)

/**
 * Maps [FfiQrException] variants to the corresponding [QrError] subtype. Any other throwable becomes the error
 * [fallback] builds from its message.
 */
private fun ffiErrorToQrError(
    throwable: Throwable,
    fallback: (String) -> QrError,
): QrError =
    when (throwable) {
        is FfiQrException ->
            when (throwable) {
                is FfiQrException.InvalidInput -> QrError.InvalidInput(throwable.message ?: "Invalid input")
                is FfiQrException.EncodingFailed -> QrError.EncodingFailed(throwable.message ?: "Encoding failed")
                is FfiQrException.DecodingFailed -> QrError.DecodingFailed(throwable.message ?: "Decoding failed")
                is FfiQrException.ContentTooLong -> QrError.ContentTooLong(throwable.message ?: "Content too long")
                is FfiQrException.ImageException -> QrError.ImageError(throwable.message ?: "Image error")
            }

        else -> fallback(throwable.message ?: "Unknown error")
    }
