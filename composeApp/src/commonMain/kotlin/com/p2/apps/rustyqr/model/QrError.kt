package com.p2.apps.rustyqr.model

/**
 * Typed error hierarchy for QR operations.
 *
 * Mirrors [FfiQrException] variants from the UniFFI-generated bindings.
 * Used as the Left side of [arrow.core.Either] throughout the QR domain.
 *
 * Mapping of [FfiQrException] subtypes, and of any other throwable, to specific variants happens in the bridge.
 */
sealed class QrError(
    val reason: String,
) {
    class InvalidInput(
        reason: String,
    ) : QrError(reason)

    class EncodingFailed(
        reason: String,
    ) : QrError(reason)

    class DecodingFailed(
        reason: String,
    ) : QrError(reason)

    class ImageError(
        reason: String,
    ) : QrError(reason)

    class ContentTooLong(
        reason: String,
    ) : QrError(reason)
}
