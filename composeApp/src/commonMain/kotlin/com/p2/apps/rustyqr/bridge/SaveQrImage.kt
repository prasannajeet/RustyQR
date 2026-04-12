package com.p2.apps.rustyqr.bridge

import arrow.core.Either

/**
 * Saves a PNG image to the device gallery / photo library.
 *
 * @return [Either.Right] on success. [Either.Left] carries a human-readable reason suitable
 *   for a Snackbar.
 */
expect suspend fun saveQrImage(
    bytes: ByteArray,
    suggestedName: String,
): Either<String, Unit>
