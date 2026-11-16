package com.p2.apps.rustyqr.debug

/**
 * Compile-time switch for recording camera frames in debug builds. Keep it `false` in committed code; set it
 * locally to `true` to collect frames, then revert. Recording additionally requires a debug build.
 */
const val CAPTURE_FRAMES = false
