package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import com.p2.apps.rustyqr.model.QrError

/**
 * The encoding half of the Rust QR library, as the Generate screen needs it.
 *
 * [QrBridge] is the production implementation. The seam lets ViewModel tests substitute a fake. Both functions
 * are blocking, so call them off the main thread.
 */
interface QrEncoder {
    /**
     * Generates a QR code PNG for [content] at [size] pixels per side.
     *
     * @return [Either.Right] with PNG bytes, or [Either.Left] with a [QrError], for example
     *   [QrError.ContentTooLong] when Rust cannot encode the text.
     */
    fun generateQrPng(
        content: String,
        size: Int,
    ): Either<QrError, ByteArray>

    /**
     * Asks Rust whether [content] fits in a QR code, using the same encoder as [generateQrPng].
     *
     * @return [Either.Right] with `true` when generation would not fail on length, `false` when it would return
     *   [QrError.ContentTooLong], or [Either.Left] with [QrError.InvalidInput] for empty content.
     */
    fun contentFits(content: String): Either<QrError, Boolean>
}
