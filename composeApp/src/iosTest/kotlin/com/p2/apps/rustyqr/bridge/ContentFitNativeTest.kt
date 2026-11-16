package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import com.p2.apps.rustyqr.model.QrError
import com.p2.apps.rustyqr.ui.mvi.GenerateQRCodeScreenIntent
import com.p2.apps.rustyqr.ui.viewmodels.GenerateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val NUMERIC_CAPACITY = 5596
private const val ALPHANUMERIC_CAPACITY = 3391
private const val BYTE_CAPACITY = 2331
private const val QR_SIZE_PX = 64
private const val DIGIT_RUN_UNIT = "id=0123456789012345678901234567890123456789;"
private val digitRunLengths = listOf(BYTE_CAPACITY, 3000, 3500, 4000, 4500)

/** Checks `contentFits` against the real Rust library through [QrBridge], on the iOS simulator. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentFitNativeTest {
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fits(content: String): Boolean = QrBridge.contentFits(content).getOrNull() ?: error("check failed")

    @Test
    fun numericBoundaryIsExact() {
        assertTrue(fits("7".repeat(NUMERIC_CAPACITY)))
        assertFalse(fits("7".repeat(NUMERIC_CAPACITY + 1)))
    }

    @Test
    fun alphanumericBoundaryIsExact() {
        assertTrue(fits("A".repeat(ALPHANUMERIC_CAPACITY)))
        assertFalse(fits("A".repeat(ALPHANUMERIC_CAPACITY + 1)))
    }

    @Test
    fun byteBoundaryIsExact() {
        assertTrue(fits("a".repeat(BYTE_CAPACITY)))
        assertFalse(fits("a".repeat(BYTE_CAPACITY + 1)))
    }

    @Test
    fun multibyteContentIsMeasuredInBytes() {
        assertTrue(fits("a" + "é".repeat((BYTE_CAPACITY - 1) / 2)))
        assertFalse(fits("é".repeat((BYTE_CAPACITY + 1) / 2)))
    }

    @Test
    fun contentFitsAgreesWithGeneration() {
        for (length in digitRunLengths) {
            val content = DIGIT_RUN_UNIT.repeat(length).take(length)
            assertEquals(fits(content), QrBridge.generateQrPng(content, QR_SIZE_PX).isRight(), "length $length")
        }
    }

    @Test
    fun blankInputIsInvalid() {
        val result: Either<QrError, Boolean> = QrBridge.contentFits("")
        assertIs<QrError.InvalidInput>(result.leftOrNull())
    }

    @Test
    fun generateScreenAcceptsWhatRustCanEncode() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val viewModel = GenerateViewModel(ioDispatcher = dispatcher, qrEncoder = QrBridge)

            viewModel.onIntent(GenerateQRCodeScreenIntent.UpdateText("7".repeat(NUMERIC_CAPACITY)))
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canGenerate)

            viewModel.onIntent(GenerateQRCodeScreenIntent.UpdateText("7".repeat(NUMERIC_CAPACITY + 1)))
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isInputTooLong)
        }
}
