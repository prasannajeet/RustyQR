package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import com.p2.apps.rustyqr.model.QrError
import com.p2.apps.rustyqr.model.ScanResult

/**
 * iOS actual — placeholder for Phase 6.
 *
 * Rust is called directly from Swift via UniFFI-generated Swift bindings (Approach B).
 * This stub satisfies the expect/actual contract so [iosMain] compiles.
 * Will be replaced in Phase 7 with real cinterop or Swift delegation.
 */
actual object QrBridge {
    actual fun generateQrPng(
        content: String,
        size: Int,
    ): Either<QrError, ByteArray> = TODO("Wired in Phase 7")

    actual fun decodeQr(imageData: ByteArray): Either<QrError, ScanResult> = TODO("Wired in Phase 7")

    actual fun decodeQrFromRaw(
        pixels: ByteArray,
        width: Int,
        height: Int,
    ): Either<QrError, ScanResult> = TODO("Wired in Phase 7")

    actual fun getLibraryVersion(): String {
        TODO("Wired in Phase 7")
    }
}
