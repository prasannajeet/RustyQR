package com.p2.apps.rustyqr.model

/**
 * Typed error hierarchy for QR operations.
 *
 * Mirrors [FfiQrException] variants from the UniFFI-generated bindings.
 * Used as the Left side of [arrow.core.Either] throughout the QR domain.
 *
 * The [from] companion is the generic fallback: it maps any [Throwable] to [DecodingFailed],
 * using the throwable's message. Mapping of [FfiQrException] subtypes to specific variants
 * happens in the bridge, not here.
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

    companion object {
        fun from(throwable: Throwable): QrError = DecodingFailed(throwable.message ?: "Unknown error")
    }
}
