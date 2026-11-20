package com.p2.apps.rustyqr.model

/** Why the camera preview stopped being usable. Reported at most once per entry into composition. */
enum class CameraFailure(
    /** Whether trying again can succeed. False when only a change outside the app, or another device, can help. */
    val isRetryable: Boolean,
) {
    /** The device has no usable camera (for example the iOS simulator, or an Android device without a back camera). */
    NoCamera(isRetryable = false),

    /** A camera exists but the session could not be set up or started, or it failed after starting. */
    CouldNotStart(isRetryable = true),

    /**
     * The device refused to open the camera (CameraX `ERROR_CAMERA_DISABLED`). Its documented causes are a device
     * policy that turned the camera off, and a client in a background process trying to open it. Trying again keeps
     * failing until something outside the app changes, so there is no retry button; the screen restarts the camera
     * when it resumes instead.
     */
    BlockedByDevice(isRetryable = false),
}
