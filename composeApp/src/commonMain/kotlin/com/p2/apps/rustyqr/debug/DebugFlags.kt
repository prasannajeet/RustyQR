package com.p2.apps.rustyqr.debug

/**
 * Compile-time switch for recording camera frames in debug builds. Keep it `false` in committed code; set it
 * locally to `true` to collect frames, then revert. Recording additionally requires a debug build and
 * [LOG_FRAME_TIMINGS] to be `false`.
 */
const val CAPTURE_FRAMES = false

/**
 * Compile-time switch for logging the per-frame timings (copy, Rust call, total) of the camera analyzer, in any build
 * type. Keep it `false` in committed code; set it locally to `true` to measure, then revert. When [CAPTURE_FRAMES] is
 * also `true`, timings win and frame recording is silently skipped, because recording's blocking file writes would
 * distort every timing. `DebugFlagsTest` fails if either flag is committed as `true`. Every timing call site is
 * guarded by this constant, so none of it runs on the frame path while it is `false`.
 */
const val LOG_FRAME_TIMINGS = false
