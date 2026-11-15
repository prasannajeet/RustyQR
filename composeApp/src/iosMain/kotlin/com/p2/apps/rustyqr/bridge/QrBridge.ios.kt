@file:OptIn(ExperimentalForeignApi::class)

package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import com.p2.apps.rustyqr.model.QrError
import com.p2.apps.rustyqr.model.ScanResult
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import rustyqrffi.RustBuffer
import rustyqrffi.RustCallStatus
import rustyqrffi.uniffi_rusty_qr_ffi_fn_func_decode_qr
import rustyqrffi.uniffi_rusty_qr_ffi_fn_func_decode_qr_from_raw
import rustyqrffi.uniffi_rusty_qr_ffi_fn_func_generate_png
import rustyqrffi.uniffi_rusty_qr_ffi_fn_func_get_library_version

/**
 * iOS actual — calls UniFFI-generated C functions via Kotlin/Native cinterop.
 *
 * C structs passed/returned by value become [CValue]<T> in Kotlin/Native.
 * The cinterop layer copies struct values in and out of native memory.
 *
 * Encoding rules (mirrored from [RustyQrFFI.swift]):
 *
 * **Lowering:**
 * - `String` → raw UTF-8 bytes, no length prefix (FfiConverterString.lower)
 * - `ByteArray` → big-endian Int32 length + raw bytes (FfiConverterData.write)
 *
 * **Lifting:**
 * - `String` from top-level fn → raw UTF-8 bytes, no length prefix
 * - `ByteArray` PNG → big-endian Int32 length + raw bytes
 * - `ScanResult.content` → big-endian Int32 length + UTF-8 bytes
 * - Error → big-endian Int32 discriminant + big-endian Int32 len + UTF-8 message
 */
actual object QrBridge {
    actual fun generateQrPng(
        content: String,
        size: Int,
    ): Either<QrError, ByteArray> =
        Either
            .catch { generatePng(content, size.toUInt()) }
            .mapLeft { rustThrowableToQrError(it) }

    actual fun decodeQr(imageData: ByteArray): Either<QrError, ScanResult> =
        Either
            .catch { decodeQrImpl(imageData) }
            .mapLeft { rustThrowableToQrError(it) }

    actual fun decodeQrFromRaw(
        pixels: ByteArray,
        width: Int,
        height: Int,
    ): Either<QrError, ScanResult> =
        Either
            .catch { decodeRawImpl(pixels, width.toUInt(), height.toUInt()) }
            .mapLeft { rustThrowableToQrError(it) }

    actual fun getLibraryVersion(): String = getVersionImpl()
}

// ---------------------------------------------------------------------------
// Concrete FFI implementations
// ---------------------------------------------------------------------------

/**
 * Shared helper that eliminates the lower → call → check → lift → free boilerplate.
 *
 * Ownership: the input [RustBuffer] is consumed by the FFI call — UniFFI's Rust
 * scaffolding takes ownership of the `RustBuffer` argument and frees its backing
 * allocation inside the function. The foreign (Kotlin) side MUST NOT call
 * `rustbuffer_free` on the input after the call; doing so is a double-free.
 *
 * [resultRb] (the output buffer owned by us) is always freed — whether [checkStatus]
 * throws or [lift] succeeds — via the `finally` block.
 *
 * @param lower  allocates the input [RustBuffer] within [this] [MemScope]
 * @param fn     calls the FFI function and returns the output [RustBuffer]
 * @param lift   converts the raw [RustBuffer] bytes into [T]
 */
private inline fun <T> callRust(
    lower: MemScope.() -> CValue<RustBuffer>,
    fn: MemScope.(CValue<RustBuffer>, CPointer<RustCallStatus>) -> CValue<RustBuffer>,
    lift: (RustBuffer) -> T,
): T =
    memScoped {
        val inputRb = lower()
        val status = alloc<RustCallStatus>()
        val resultRb = fn(inputRb, status.ptr)
        try {
            checkStatus(status)
            resultRb.useContents { lift(this) }
        } finally {
            freeRustBuffer(resultRb)
        }
    }

private fun generatePng(
    content: String,
    size: UInt,
): ByteArray =
    callRust(
        lower = { lowerString(content) },
        fn = { inputRb, statusPtr -> uniffi_rusty_qr_ffi_fn_func_generate_png(inputRb, size, statusPtr) },
        lift = { liftData(it) },
    )

private fun decodeQrImpl(imageData: ByteArray): ScanResult =
    callRust(
        lower = { lowerData(imageData) },
        fn = { inputRb, statusPtr -> uniffi_rusty_qr_ffi_fn_func_decode_qr(inputRb, statusPtr) },
        lift = { liftScanResult(it) },
    )

private fun decodeRawImpl(
    pixels: ByteArray,
    width: UInt,
    height: UInt,
): ScanResult =
    callRust(
        lower = { lowerData(pixels) },
        fn = { inputRb, statusPtr ->
            uniffi_rusty_qr_ffi_fn_func_decode_qr_from_raw(inputRb, width, height, statusPtr)
        },
        lift = { liftScanResult(it) },
    )

private fun getVersionImpl(): String =
    memScoped {
        val status = alloc<RustCallStatus>()
        val resultRb = uniffi_rusty_qr_ffi_fn_func_get_library_version(status.ptr)
        try {
            checkStatus(status)
            resultRb.useContents { liftRawString(this) }
        } finally {
            freeRustBuffer(resultRb)
        }
    }
