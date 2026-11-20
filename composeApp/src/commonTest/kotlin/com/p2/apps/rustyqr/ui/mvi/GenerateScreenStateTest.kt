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
    fun noVerdictNeverBlocks() {
        val state = GenerateScreenState(inputText = "abc")
        assertFalse(state.isInputTooLong)
        assertTrue(state.canGenerate)
    }

    @Test
    fun falseVerdictForTheTrimmedTextBlocks() {
        val state = GenerateScreenState(inputText = "  abc \n", contentFit = ContentFit("abc", fits = false))
        assertTrue(state.isInputTooLong)
        assertFalse(state.canGenerate)
    }

    @Test
    fun staleVerdictIsIgnored() {
        val state = GenerateScreenState(inputText = "abcd", contentFit = ContentFit("abc", fits = false))
        assertFalse(state.isInputTooLong)
        assertTrue(state.canGenerate)
    }

    @Test
    fun fitsVerdictAllowsGeneration() {
        val state = GenerateScreenState(inputText = "abc", contentFit = ContentFit("abc", fits = true))
        assertFalse(state.isInputTooLong)
        assertTrue(state.canGenerate)
    }

    @Test
    fun hintStaysWhileEditingOverLimitText() {
        val state = GenerateScreenState(inputText = "abcd", contentFit = ContentFit("abc", fits = false))
        assertTrue(state.showsTooLongHint)
        assertFalse(state.isInputTooLong)
    }

    @Test
    fun hintHidesWhenTextIsBlankOrVerdictFits() {
        val tooLong = ContentFit("abc", fits = false)
        val fitting = ContentFit("abc", fits = true)
        assertFalse(GenerateScreenState(inputText = "  ", contentFit = tooLong).showsTooLongHint)
        assertFalse(GenerateScreenState(inputText = "abc", contentFit = fitting).showsTooLongHint)
        assertFalse(GenerateScreenState(inputText = "abc").showsTooLongHint)
    }

    @Test
    fun statesDifferingOnlyInContentFitAreNotEqual() {
        val first = GenerateScreenState(inputText = "a", contentFit = ContentFit("a", fits = true))
        val second = GenerateScreenState(inputText = "a", contentFit = ContentFit("a", fits = false))
        assertNotEquals(first, second)
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
