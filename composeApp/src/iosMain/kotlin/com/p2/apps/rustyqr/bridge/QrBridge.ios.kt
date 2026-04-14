@file:OptIn(ExperimentalForeignApi::class)

package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import com.p2.apps.rustyqr.model.QrError
import com.p2.apps.rustyqr.model.ScanResult
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.useContents
import rustyqrffi.ForeignBytes
import rustyqrffi.RustBuffer
import rustyqrffi.RustCallStatus
import rustyqrffi.ffi_rusty_qr_ffi_rustbuffer_free
import rustyqrffi.ffi_rusty_qr_ffi_rustbuffer_from_bytes
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

// ---------------------------------------------------------------------------
// Buffer lowering (Kotlin → Rust)
// ---------------------------------------------------------------------------

/**
 * Lowers [value] as raw UTF-8 bytes (no length prefix).
 * Mirrors FfiConverterString.lower in [RustyQrFFI.swift].
 *
 * Extension on [MemScope] so the returned [CValue]<[RustBuffer]> shares the
 * caller's memory scope — no nested [memScoped] block needed.
 * Caller must free the returned buffer with [freeRustBuffer].
 */
private fun MemScope.lowerString(value: String): CValue<RustBuffer> {
    val utf8 = value.encodeToByteArray()
    val uBytes = utf8.asUByteArray().toCValues()
    val fb =
        alloc<ForeignBytes>().apply {
            len = utf8.size
            data = uBytes.getPointer(this@lowerString)
        }
    val status = alloc<RustCallStatus>()
    val rb = ffi_rusty_qr_ffi_rustbuffer_from_bytes(fb.readValue(), status.ptr)
    checkStatus(status)
    return rb
}

/**
 * Lowers [bytes] as big-endian Int32 length + raw bytes.
 * Mirrors FfiConverterData.write in [RustyQrFFI.swift].
 *
 * Extension on [MemScope] so the returned [CValue]<[RustBuffer]> shares the
 * caller's memory scope — no nested [memScoped] block needed.
 * Caller must free the returned buffer with [freeRustBuffer].
 */
private fun MemScope.lowerData(bytes: ByteArray): CValue<RustBuffer> {
    val len = bytes.size
    val payload = ByteArray(4 + len)
    payload[0] = (len ushr 24).toByte()
    payload[1] = (len ushr 16).toByte()
    payload[2] = (len ushr 8).toByte()
    payload[3] = len.toByte()
    bytes.copyInto(payload, destinationOffset = 4)
    val uBytes = payload.asUByteArray().toCValues()
    val fb =
        alloc<ForeignBytes>().apply {
            this.len = payload.size
            data = uBytes.getPointer(this@lowerData)
        }
    val status = alloc<RustCallStatus>()
    val rb = ffi_rusty_qr_ffi_rustbuffer_from_bytes(fb.readValue(), status.ptr)
    checkStatus(status)
    return rb
}

// ---------------------------------------------------------------------------
// Buffer lifting (Rust → Kotlin)
// ---------------------------------------------------------------------------

/**
 * Lifts [rb] as raw UTF-8 bytes (no length prefix).
 * Mirrors FfiConverterString.lift in [RustyQrFFI.swift].
 */
private fun liftRawString(rb: RustBuffer): String {
    if (rb.len == 0uL || rb.data == null) return ""
    return rb.data!!
        .reinterpret<ByteVar>()
        .readBytes(rb.len.toInt())
        .decodeToString()
}

/**
 * Lifts [rb] as big-endian Int32 length + raw bytes.
 * Mirrors FfiConverterData.read in [RustyQrFFI.swift].
 */
private fun liftData(rb: RustBuffer): ByteArray {
    if (rb.len == 0uL || rb.data == null) return ByteArray(0)
    val all = rb.data!!.reinterpret<ByteVar>().readBytes(rb.len.toInt())
    val dataLen = readInt32BigEndianFromArray(all, 0)
    if (dataLen <= 0) return ByteArray(0)
    return all.copyOfRange(4, 4 + dataLen)
}

/**
 * Lifts [rb] as a [ScanResult].
 * Format: FfiConverterString.read(content) = big-endian Int32 len + UTF-8 bytes.
 * Mirrors FfiConverterTypeFfiScanResult.read in [RustyQrFFI.swift].
 */
private fun liftScanResult(rb: RustBuffer): ScanResult {
    if (rb.len == 0uL || rb.data == null) return ScanResult(content = "")
    val all = rb.data!!.reinterpret<ByteVar>().readBytes(rb.len.toInt())
    return ScanResult(content = readFfiStringFromArray(all, offset = 0))
}

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
private fun checkStatus(status: RustCallStatus) {
    when (status.code.toInt()) {
        0 -> return
        1 -> {
            val eb = status.errorBuf
            val bytes = readRustBufferBytes(eb)
            freeRustBuffer(eb.readValue())
            if (bytes.size < 4) throw RustCallException.Panic("Truncated error buffer")
            val discriminant = readInt32BigEndianFromArray(bytes, 0)
            val message = if (bytes.size > 4) readFfiStringFromArray(bytes, 4) else "Unknown"
            throw RustCallException.UserError(discriminant, message)
        }
        else -> {
            val eb = status.errorBuf
            val message =
                if (eb.len > 0uL && eb.data != null) {
                    eb.data!!
                        .reinterpret<ByteVar>()
                        .readBytes(eb.len.toInt())
                        .decodeToString()
                } else {
                    "Rust panic (no message)"
                }
            freeRustBuffer(eb.readValue())
            throw RustCallException.Panic("Rust panic: $message")
        }
    }
}

// ---------------------------------------------------------------------------
// Memory management
// ---------------------------------------------------------------------------

private fun freeRustBuffer(rb: CValue<RustBuffer>) =
    memScoped {
        val status = alloc<RustCallStatus>()
        ffi_rusty_qr_ffi_rustbuffer_free(rb, status.ptr)
    }

private fun readRustBufferBytes(rb: RustBuffer): ByteArray {
    if (rb.len == 0uL || rb.data == null) return ByteArray(0)
    return rb.data!!.reinterpret<ByteVar>().readBytes(rb.len.toInt())
}

// ---------------------------------------------------------------------------
// Binary reading helpers
// ---------------------------------------------------------------------------

private fun readInt32BigEndianFromArray(
    bytes: ByteArray,
    offset: Int,
): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

private fun readFfiStringFromArray(
    bytes: ByteArray,
    offset: Int,
): String {
    if (offset + 4 > bytes.size) return ""
    val strLen = readInt32BigEndianFromArray(bytes, offset)
    if (strLen <= 0) return ""
    val end = offset + 4 + strLen
    if (end > bytes.size) return ""
    return bytes.decodeToString(startIndex = offset + 4, endIndex = end)
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
private fun rustThrowableToQrError(throwable: Throwable): QrError =
    when (throwable) {
        is RustCallException.UserError ->
            when (throwable.discriminant) {
                1 -> QrError.InvalidInput(throwable.errorMessage)
                2 -> QrError.EncodingFailed(throwable.errorMessage)
                3 -> QrError.DecodingFailed(throwable.errorMessage)
                4 -> QrError.ImageError(throwable.errorMessage)
                else -> QrError.DecodingFailed(throwable.errorMessage)
            }
        else -> QrError.from(throwable)
    }
