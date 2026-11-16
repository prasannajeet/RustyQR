package com.p2.apps.rustyqr.model

/** Why the camera preview stopped being usable. Reported at most once per entry into composition. */
enum class CameraFailure(
    /** Whether trying again can succeed. False when the device has no camera at all. */
    val isRetryable: Boolean,
) {
    /** The device has no usable camera (for example the iOS simulator, or an Android device without a back camera). */
    NoCamera(isRetryable = false),

    /** A camera exists but the session could not be set up or started, or it failed after starting. */
    CouldNotStart(isRetryable = true),
}
