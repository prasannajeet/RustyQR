package com.p2.apps.rustyqr.ui.mvi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QrInputLimitTest {
    private fun inputOfLength(length: Int): String = "a".repeat(length)

    @Test
    fun inputAtTheLimitIsNotTooLong() {
        assertFalse(isQrInputTooLong(inputOfLength(MAX_QR_INPUT_LENGTH)))
    }

    @Test
    fun surroundingWhitespaceDoesNotCountTowardsTheLimit() {
        val padded = "  \n" + inputOfLength(MAX_QR_INPUT_LENGTH) + "\t  "
        assertTrue(padded.length > MAX_QR_INPUT_LENGTH)
        assertFalse(isQrInputTooLong(padded))
    }

    @Test
    fun inputOneOverTheLimitIsTooLong() {
        assertTrue(isQrInputTooLong(inputOfLength(MAX_QR_INPUT_LENGTH + 1)))
    }

    @Test
    fun overLimitInputStaysTooLongWithSurroundingWhitespace() {
        assertTrue(isQrInputTooLong(" " + inputOfLength(MAX_QR_INPUT_LENGTH + 1) + " "))
    }

    @Test
    fun whitespaceOnlyInputIsNotTooLong() {
        assertFalse(isQrInputTooLong(" ".repeat(MAX_QR_INPUT_LENGTH + 10)))
    }

    @Test
    fun onlySurroundingWhitespaceIsIgnored() {
        val innerWhitespace = "a" + " ".repeat(MAX_QR_INPUT_LENGTH - 1) + "a"
        assertTrue(isQrInputTooLong(innerWhitespace))
    }
}
