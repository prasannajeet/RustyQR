package com.p2.apps.rustyqr.debug

import kotlin.test.Test
import kotlin.test.assertFalse

class DebugFlagsTest {
    @Test
    fun debugFlagsAreOffInCommittedCode() {
        assertFalse(CAPTURE_FRAMES, "CAPTURE_FRAMES must not ship switched on")
        assertFalse(LOG_FRAME_TIMINGS, "LOG_FRAME_TIMINGS must not ship switched on")
    }
}
