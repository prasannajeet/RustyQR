package com.p2.apps.rustyqr.ui.mvi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GenerateScreenStateTest {
    @Test
    fun emptyInputCannotGenerate() {
        val state = GenerateScreenState(inputText = "")
        assertFalse(state.isInputTooLong)
        assertFalse(state.canGenerate)
    }

    @Test
    fun blankInputCannotGenerate() {
        assertFalse(GenerateScreenState(inputText = "  \n\t ").canGenerate)
    }

    @Test
    fun inputAtTheLimitCanGenerate() {
        val state = GenerateScreenState(inputText = "a".repeat(MAX_QR_INPUT_LENGTH))
        assertFalse(state.isInputTooLong)
        assertTrue(state.canGenerate)
    }

    @Test
    fun inputOverTheLimitIsTooLongAndCannotGenerate() {
        val state = GenerateScreenState(inputText = "a".repeat(MAX_QR_INPUT_LENGTH + 1))
        assertTrue(state.isInputTooLong)
        assertFalse(state.canGenerate)
    }

    @Test
    fun statesWithEqualByteContentsAreEqual() {
        val first = GenerateScreenState(qrImageBytes = byteArrayOf(1, 2))
        val second = GenerateScreenState(qrImageBytes = byteArrayOf(1, 2))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun statesWithDifferentByteContentsAreNotEqual() {
        val first = GenerateScreenState(qrImageBytes = byteArrayOf(1, 2))
        val second = GenerateScreenState(qrImageBytes = byteArrayOf(1, 3))
        assertNotEquals(first, second)
    }

    @Test
    fun statesWithDifferentInputTextAreNotEqual() {
        assertNotEquals(GenerateScreenState(inputText = "a"), GenerateScreenState(inputText = "b"))
    }
}
