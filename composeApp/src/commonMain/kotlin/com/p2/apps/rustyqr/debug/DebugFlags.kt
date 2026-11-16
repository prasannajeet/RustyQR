package com.p2.apps.rustyqr.debug

/**
 * Compile-time switch for recording camera frames in debug builds. Keep it `false` in committed code; set it
 * locally to `true` to collect frames, then revert. Recording additionally requires a debug build.
 */
const val CAPTURE_FRAMES = false

/**
 * Compile-time switch for logging the per-frame timings (copy, Rust call, total) of the camera analyzer, in any build
 * type. Keep it `false` in committed code; set it locally to `true` to measure, then revert. When [CAPTURE_FRAMES] is
 * also `true`, no timings are taken and one warning is logged: recording keeps running, because recording's blocking
 * file writes would distort every timing.
 */
const val LOG_FRAME_TIMINGS = false
