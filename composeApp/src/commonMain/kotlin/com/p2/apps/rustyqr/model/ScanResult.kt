package com.p2.apps.rustyqr.model

/**
 * Result of a successful QR decode operation.
 *
 * Mirrors [FfiScanResult] from the UniFFI-generated Android bindings.
 * Defined in commonMain so MVI state can reference it without platform imports.
 */
data class ScanResult(
    val content: String,
)
