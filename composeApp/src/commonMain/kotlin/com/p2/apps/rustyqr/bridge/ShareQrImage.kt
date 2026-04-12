package com.p2.apps.rustyqr.bridge

import arrow.core.Either

/**
 * Launches the platform share sheet with a PNG image.
 *
 * @return [Either.Right] on success. [Either.Left] carries a human-readable reason suitable
 *   for a Snackbar.
 */
expect suspend fun shareQrImage(
    bytes: ByteArray,
    suggestedName: String,
): Either<String, Unit>
