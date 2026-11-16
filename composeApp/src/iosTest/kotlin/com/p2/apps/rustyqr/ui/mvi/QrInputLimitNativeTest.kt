package com.p2.apps.rustyqr.ui.mvi

import com.p2.apps.rustyqr.rust.maxContentBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class QrInputLimitNativeTest {
    @Test
    fun inputLimitMatchesTheRustCapacity() {
        assertEquals(maxContentBytes().toInt(), MAX_QR_INPUT_BYTES)
    }
}
