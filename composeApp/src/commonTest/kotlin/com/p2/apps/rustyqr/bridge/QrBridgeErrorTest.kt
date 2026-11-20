package com.p2.apps.rustyqr.bridge

import com.p2.apps.rustyqr.model.QrError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QrBridgeErrorTest {
    @Test
    fun aNonFfiThrowableOnTheEncodePathIsAnEncodingFailure() {
        val error = ffiErrorToQrError(IllegalStateException("boom"), fallback = QrError::EncodingFailed)

        assertIs<QrError.EncodingFailed>(error)
        assertEquals("boom", error.reason)
    }

    @Test
    fun aNonFfiThrowableOnTheDecodePathIsADecodingFailure() {
        val error = ffiErrorToQrError(IllegalStateException("boom"), fallback = QrError::DecodingFailed)

        assertIs<QrError.DecodingFailed>(error)
        assertEquals("boom", error.reason)
    }

    @Test
    fun aThrowableWithoutAMessageStillHasAReason() {
        val error = ffiErrorToQrError(IllegalStateException(), fallback = QrError::EncodingFailed)

        assertEquals("Unknown error", error.reason)
    }
}
