package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import com.p2.apps.rustyqr.model.QrError
import com.p2.apps.rustyqr.model.ScanResult

/**
 * Bridge to the Rust QR library.
 *
 * All functions are synchronous and MUST be called from [kotlinx.coroutines.Dispatchers.IO],
 * never the main thread — each call crosses the JNI boundary.
 *
 * Platform actuals call UniFFI-generated Kotlin bindings (Android) or
 * Swift FFI bindings (iOS Phase 7).
 *
 * Error contract: functions return [Either.Left] with a [QrError] on domain errors.
 * Callers use [Either.fold] or [Either.onRight] — no try/catch required.
 */
expect object QrBridge {
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
    ): Either<QrError, ByteArray>

    /**
     * Decodes a QR code from PNG or JPEG image bytes.
     *
     * @param imageData Raw PNG or JPEG bytes.
     * @return [Either.Right] with [ScanResult], or [Either.Left] with [QrError] on failure.
     */
    fun decodeQr(imageData: ByteArray): Either<QrError, ScanResult>

    /**
     * Decodes a QR code from a raw grayscale (Y-plane) pixel buffer.
     *
     * This is the camera-frame path — avoids PNG round-trip overhead.
     * Most camera frames will return [Either.Left] (no QR found); callers
     * should use [Either.onRight] and silently discard Left values.
     *
     * @param pixels Raw luma bytes (Y plane from [android.media.Image]).
     * @param width  Frame width in pixels.
     * @param height Frame height in pixels.
     * @return [Either.Right] with [ScanResult], or [Either.Left] with [QrError] on failure.
     */
    fun decodeQrFromRaw(
        pixels: ByteArray,
        width: Int,
        height: Int,
    ): Either<QrError, ScanResult>

    /**
     * Returns the Rust library version string (from [rusty_qr_core] Cargo.toml).
     */
    fun getLibraryVersion(): String
}
