package com.p2.apps.rustyqr.bridge

/**
 * Triggers a short haptic tap feedback on the device.
 *
 * Used when a QR code is successfully decoded.
 * Must be called from the main thread.
 */
expect fun triggerHaptic()
