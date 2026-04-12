package com.p2.apps.rustyqr.bridge

import arrow.core.Either

/**
 * iOS placeholder — real UIActivityViewController wiring lands in Phase 7.
 */
actual suspend fun shareQrImage(
    bytes: ByteArray,
    suggestedName: String,
): Either<String, Unit> = Either.Left("Share is not supported on iOS yet")
