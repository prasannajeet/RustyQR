package com.p2.apps.rustyqr.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapturePolicyTest {
    @Test
    fun acceptsEveryFifteenthUndecodedFrame() {
        val policy = CapturePolicy()
        val accepted = (1..45).filter { policy.accept(decoded = false) }
        assertEquals(listOf(15, 30, 45), accepted)
    }

    @Test
    fun acceptsEveryDecodedFrame() {
        val policy = CapturePolicy()
        assertTrue(policy.accept(decoded = true))
        assertFalse(policy.accept(decoded = false))
        assertTrue(policy.accept(decoded = true))
    }

    @Test
    fun stopsAfterFortyAcceptedFrames() {
        val policy = CapturePolicy()
        val accepted = (1..100).count { policy.accept(decoded = true) }
        assertEquals(40, accepted)
        assertFalse(policy.accept(decoded = true))
        assertFalse(policy.accept(decoded = false))
    }
}
