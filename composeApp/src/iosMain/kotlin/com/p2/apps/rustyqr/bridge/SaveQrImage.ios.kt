package com.p2.apps.rustyqr.bridge

import arrow.core.Either

/**
 * iOS placeholder — real Photos library write lands in Phase 7.
 */
actual suspend fun saveQrImage(
    bytes: ByteArray,
    suggestedName: String,
): Either<String, Unit> = Either.Left("Save is not supported on iOS yet")
