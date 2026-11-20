@file:OptIn(ExperimentalForeignApi::class)

package com.p2.apps.rustyqr.bridge

import com.p2.apps.rustyqr.model.ScanResult
import kotlinx.cinterop.ByteVar
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
import rustyqrffi.ForeignBytes
import rustyqrffi.RustBuffer
import rustyqrffi.RustCallStatus
import rustyqrffi.ffi_rusty_qr_ffi_rustbuffer_free
import rustyqrffi.ffi_rusty_qr_ffi_rustbuffer_from_bytes

// UniFFI wire format: lengths are big-endian Int32 values (4 bytes) followed by the payload.
internal const val INT32_SIZE_BYTES = 4
private const val LAST_INT32_BYTE_INDEX = 3
private const val BYTE_MASK = 0xFF
private const val SHIFT_TO_BYTE_0 = 24
private const val SHIFT_TO_BYTE_1 = 16
private const val SHIFT_TO_BYTE_2 = 8

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
internal fun MemScope.lowerString(value: String): CValue<RustBuffer> {
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
internal fun MemScope.lowerData(bytes: ByteArray): CValue<RustBuffer> {
    val len = bytes.size
    val payload = ByteArray(INT32_SIZE_BYTES + len)
    payload[0] = (len ushr SHIFT_TO_BYTE_0).toByte()
    payload[1] = (len ushr SHIFT_TO_BYTE_1).toByte()
    payload[2] = (len ushr SHIFT_TO_BYTE_2).toByte()
    payload[LAST_INT32_BYTE_INDEX] = len.toByte()
    bytes.copyInto(payload, destinationOffset = INT32_SIZE_BYTES)
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
internal fun liftRawString(rb: RustBuffer): String {
    val data = rb.data
    if (rb.len == 0uL || data == null) return ""
    return data
        .reinterpret<ByteVar>()
        .readBytes(rb.len.toInt())
        .decodeToString()
}

/**
 * Lifts [rb] as big-endian Int32 length + raw bytes.
 * Mirrors FfiConverterData.read in [RustyQrFFI.swift].
 */
internal fun liftData(rb: RustBuffer): ByteArray {
    val all = readRustBufferBytes(rb)
    val dataLen = if (all.isEmpty()) 0 else readInt32BigEndianFromArray(all, 0)
    return if (dataLen > 0) all.copyOfRange(INT32_SIZE_BYTES, INT32_SIZE_BYTES + dataLen) else ByteArray(0)
}

/**
 * Lifts [rb] as a [ScanResult].
 * Format: FfiConverterString.read(content) = big-endian Int32 len + UTF-8 bytes.
 * Mirrors FfiConverterTypeFfiScanResult.read in [RustyQrFFI.swift].
 */
internal fun liftScanResult(rb: RustBuffer): ScanResult {
    val all = readRustBufferBytes(rb)
    if (all.isEmpty()) return ScanResult(content = "")
    return ScanResult(content = readFfiStringFromArray(all, offset = 0))
}

// ---------------------------------------------------------------------------
// Memory management
// ---------------------------------------------------------------------------

internal fun freeRustBuffer(rb: CValue<RustBuffer>) =
    memScoped {
        val status = alloc<RustCallStatus>()
        ffi_rusty_qr_ffi_rustbuffer_free(rb, status.ptr)
    }

internal fun readRustBufferBytes(rb: RustBuffer): ByteArray {
    val data = rb.data
    if (rb.len == 0uL || data == null) return ByteArray(0)
    return data.reinterpret<ByteVar>().readBytes(rb.len.toInt())
}

// ---------------------------------------------------------------------------
// Binary reading helpers
// ---------------------------------------------------------------------------

internal fun readInt32BigEndianFromArray(
    bytes: ByteArray,
    offset: Int,
): Int =
    ((bytes[offset].toInt() and BYTE_MASK) shl SHIFT_TO_BYTE_0) or
        ((bytes[offset + 1].toInt() and BYTE_MASK) shl SHIFT_TO_BYTE_1) or
        ((bytes[offset + 2].toInt() and BYTE_MASK) shl SHIFT_TO_BYTE_2) or
        (bytes[offset + LAST_INT32_BYTE_INDEX].toInt() and BYTE_MASK)

internal fun readFfiStringFromArray(
    bytes: ByteArray,
    offset: Int,
): String {
    if (offset + INT32_SIZE_BYTES > bytes.size) return ""
    val strLen = readInt32BigEndianFromArray(bytes, offset)
    val start = offset + INT32_SIZE_BYTES
    val end = start + strLen
    return if (strLen > 0 && end <= bytes.size) bytes.decodeToString(startIndex = start, endIndex = end) else ""
}
