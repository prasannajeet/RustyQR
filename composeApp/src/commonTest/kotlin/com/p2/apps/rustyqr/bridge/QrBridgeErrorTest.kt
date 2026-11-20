package com.p2.apps.rustyqr.bridge

import com.p2.apps.rustyqr.model.QrError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QrBridgeErrorTest {
    @Test
    fun aNonFfiThrowableOnTheEncodePathIsAnEncodingFailure() {
        val error = encodeError(IllegalStateException("boom"))

        assertIs<QrError.EncodingFailed>(error)
        assertEquals("boom", error.reason)
    }

    @Test
    fun aNonFfiThrowableOnTheDecodePathIsADecodingFailure() {
        val error = decodeError(IllegalStateException("boom"))

        assertIs<QrError.DecodingFailed>(error)
        assertEquals("boom", error.reason)
    }

    @Test
    fun aThrowableWithoutAMessageStillHasAReason() {
        assertEquals("Unknown error", encodeError(IllegalStateException()).reason)
    }
}
