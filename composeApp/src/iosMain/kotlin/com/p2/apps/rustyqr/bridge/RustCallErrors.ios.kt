@file:OptIn(ExperimentalForeignApi::class)

package com.p2.apps.rustyqr.bridge

import com.p2.apps.rustyqr.model.QrError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import rustyqrffi.RustCallStatus

// Discriminants of the Rust QrError enum as serialised by UniFFI (keep in sync with rustySDK).
private const val DISCRIMINANT_INVALID_INPUT = 1
private const val DISCRIMINANT_ENCODING_FAILED = 2
private const val DISCRIMINANT_DECODING_FAILED = 3
private const val DISCRIMINANT_IMAGE_ERROR = 4

// ---------------------------------------------------------------------------
// Status check — must be called inside memScoped
// ---------------------------------------------------------------------------

/**
 * Inspects [status.code]:
 * - 0 → success
 * - 1 → user error: reads discriminant + message and throws [RustCallException.UserError]
 * - else → Rust panic: reads raw UTF-8 message and throws [RustCallException.Panic]
 *
 * Frees [RustCallStatus.errorBuf] in all error cases.
 */
internal fun checkStatus(status: RustCallStatus) {
    when (status.code.toInt()) {
        0 -> return
        1 -> throw userError(status)
        else -> throw panicError(status)
    }
}

/** Reads and frees [RustCallStatus.errorBuf], returning the exception for a Rust user error (code 1). */
private fun userError(status: RustCallStatus): RustCallException {
    val eb = status.errorBuf
    val bytes = readRustBufferBytes(eb)
    freeRustBuffer(eb.readValue())
    if (bytes.size < INT32_SIZE_BYTES) return RustCallException.Panic("Truncated error buffer")
    val discriminant = readInt32BigEndianFromArray(bytes, 0)
    val message = if (bytes.size > INT32_SIZE_BYTES) readFfiStringFromArray(bytes, INT32_SIZE_BYTES) else "Unknown"
    return RustCallException.UserError(discriminant, message)
}

/** Reads and frees [RustCallStatus.errorBuf], returning the exception for a Rust panic. */
private fun panicError(status: RustCallStatus): RustCallException {
    val eb = status.errorBuf
    val bytes = readRustBufferBytes(eb)
    val message = if (bytes.isNotEmpty()) bytes.decodeToString() else "Rust panic (no message)"
    freeRustBuffer(eb.readValue())
    return RustCallException.Panic("Rust panic: $message")
}

// ---------------------------------------------------------------------------
// Exception types
// ---------------------------------------------------------------------------

private sealed class RustCallException(
    message: String,
) : Exception(message) {
    class UserError(
        val discriminant: Int,
        val errorMessage: String,
    ) : RustCallException("Rust error ($discriminant): $errorMessage")

    class Panic(
        panicMessage: String,
    ) : RustCallException(panicMessage)
}

// ---------------------------------------------------------------------------
// Error mapping
// ---------------------------------------------------------------------------

/**
 * Maps [RustCallException] variants to [QrError] subtypes.
 *
 * Error discriminant values (1-4) must match FfiConverterTypeFfiQrError.read in
 * iosApp/generated/RustyQrFFI.swift. Regenerating Swift bindings triggers a review of
 * this mapping — add new variants here if Rust's QrError enum gains new arms.
 */
internal fun rustThrowableToQrError(throwable: Throwable): QrError =
    when (throwable) {
        is RustCallException.UserError ->
            when (throwable.discriminant) {
                DISCRIMINANT_INVALID_INPUT -> QrError.InvalidInput(throwable.errorMessage)
                DISCRIMINANT_ENCODING_FAILED -> QrError.EncodingFailed(throwable.errorMessage)
                DISCRIMINANT_DECODING_FAILED -> QrError.DecodingFailed(throwable.errorMessage)
                DISCRIMINANT_IMAGE_ERROR -> QrError.ImageError(throwable.errorMessage)
                else -> QrError.DecodingFailed(throwable.errorMessage)
            }
        else -> QrError.from(throwable)
    }
