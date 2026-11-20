package com.p2.apps.rustyqr.debug

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class RecordingEnabledTest {
    @Test
    fun timingsWinWhenBothFlagsAreTrueAndTheConflictIsWarnedExactlyOnce() {
        val warnings = mutableListOf<String>()
        val guard = AtomicBoolean(false)
        repeat(2) {
            val enabled =
                recordingEnabled(
                    captureFrames = true,
                    logFrameTimings = true,
                    isDebug = { error("debug check must not run") },
                    warn = warnings::add,
                    warnedOnce = guard,
                )
            assertFalse(enabled)
        }
        assertEquals(1, warnings.size)
        assertTrue("LOG_FRAME_TIMINGS" in warnings.single(), warnings.single())
    }

    @Test
    fun recordsOnlyWhenCapturingWithoutTimingsInADebugBuild() {
        val warnings = mutableListOf<String>()

        fun enabled(
            capture: Boolean,
            timings: Boolean,
            debug: Boolean,
        ) = recordingEnabled(capture, timings, { debug }, warnings::add, AtomicBoolean(false))
        assertTrue(enabled(capture = true, timings = false, debug = true))
        assertFalse(enabled(capture = true, timings = false, debug = false))
        assertFalse(enabled(capture = false, timings = false, debug = true))
        assertFalse(enabled(capture = false, timings = true, debug = true))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun disabledConstantsNeverEvaluateTheBuildType() {
        assertFalse(
            recordingEnabled(
                captureFrames = false,
                logFrameTimings = false,
                isDebug = { error("debug check must not run") },
            ),
        )
    }
}
