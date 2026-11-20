package com.p2.apps.rustyqr.ui.mvi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QrInputLimitTest {
    private fun inputOfLength(length: Int): String = "a".repeat(length)

    @Test
    fun inputAtTheLimitIsNotTooLong() {
        assertFalse(isQrInputTooLong(inputOfLength(MAX_QR_INPUT_BYTES)))
    }

    @Test
    fun surroundingWhitespaceDoesNotCountTowardsTheLimit() {
        val padded = "  \n" + inputOfLength(MAX_QR_INPUT_BYTES) + "\t  "
        assertTrue(padded.length > MAX_QR_INPUT_BYTES)
        assertFalse(isQrInputTooLong(padded))
    }

    @Test
    fun inputOneOverTheLimitIsTooLong() {
        assertTrue(isQrInputTooLong(inputOfLength(MAX_QR_INPUT_BYTES + 1)))
    }

    @Test
    fun overLimitInputStaysTooLongWithSurroundingWhitespace() {
        assertTrue(isQrInputTooLong(" " + inputOfLength(MAX_QR_INPUT_BYTES + 1) + " "))
    }

    @Test
    fun whitespaceOnlyInputIsNotTooLong() {
        assertFalse(isQrInputTooLong(" ".repeat(MAX_QR_INPUT_BYTES + 10)))
    }

    @Test
    fun onlySurroundingWhitespaceIsIgnored() {
        val innerWhitespace = "a" + " ".repeat(MAX_QR_INPUT_BYTES - 1) + "a"
        assertTrue(isQrInputTooLong(innerWhitespace))
    }

    @Test
    fun multiByteInputAtTheByteLimitIsNotTooLong() {
        val text = "a" + "é".repeat((MAX_QR_INPUT_BYTES - 1) / 2)
        assertEquals(MAX_QR_INPUT_BYTES, text.encodeToByteArray().size)
        assertFalse(isQrInputTooLong(text))
    }

    @Test
    fun multiByteInputOneByteOverTheLimitIsTooLong() {
        val text = "é".repeat((MAX_QR_INPUT_BYTES + 1) / 2)
        assertEquals(MAX_QR_INPUT_BYTES + 1, text.encodeToByteArray().size)
        assertTrue(isQrInputTooLong(text))
    }

    @Test
    fun fourByteEmojiCountsAsFourBytes() {
        val emoji = "\uD83D\uDE00"
        assertEquals(4, emoji.encodeToByteArray().size)
        assertFalse(isQrInputTooLong(emoji.repeat(MAX_QR_INPUT_BYTES / 4)))
        assertTrue(isQrInputTooLong(emoji.repeat(MAX_QR_INPUT_BYTES / 4 + 1)))
    }
}
