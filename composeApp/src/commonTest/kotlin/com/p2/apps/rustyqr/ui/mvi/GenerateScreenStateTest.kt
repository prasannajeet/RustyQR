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
    fun qrPngComparesByContent() {
        assertEquals(QrPng(byteArrayOf(1, 2)), QrPng(byteArrayOf(1, 2)))
        assertEquals(QrPng(byteArrayOf(1, 2)).hashCode(), QrPng(byteArrayOf(1, 2)).hashCode())
        assertNotEquals(QrPng(byteArrayOf(1, 2)), QrPng(byteArrayOf(1, 3)))
    }
}
