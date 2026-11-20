package com.p2.apps.rustyqr.bridge

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val NUMERIC_CAPACITY = 5596
private const val BYTE_CAPACITY = 2331

// Non-ASCII content is UTF-8 behind a 12-bit ECI header (4-bit mode + 8-bit designator). Version 40-M holds 18672
// data bits; a 2331-byte segment uses 4 + 16 + 8 * 2331 = 18668, 4 bits short of 18672. The 12-bit header takes
// those 4 bits plus one whole byte, so 2331 becomes 2330.
private const val UTF8_CAPACITY = 2330

// Whole pixels per module: version 40 needs at least 185 px, so smaller sizes are refused at render time.
private const val QR_SIZE_PX = 256
private const val MIXED_UNIT = "id=0123456789012345678901234567890123456789;"

// MIXED_UNIT is 44 characters: "id=" and ";" are bytes, the 40 digits are numeric. At version 40 a byte segment costs
// 4 + 16 + 8n bits and a numeric one 4 + 14 + 10 per 3 digits. The best split joins ";id=" into one 52-bit byte
// segment and sends each 40-digit run to a 152-bit numeric segment (4 + 14 + 13 * 10 + 4 for the last digit), so a
// period costs about 204 bits.
// 3500 chars: 79 periods and a 24-char tail, about 79 * 204 + 44 + 88 = 16248 bits, under the 18672 of 40-M.
// 4500 chars: 102 periods and a 12-char tail, about 102 * 204 + 44 + 48 = 20900 bits, over 18672.
private const val MIXED_FITTING_LENGTH = 3500
private const val MIXED_OVERFLOWING_LENGTH = 4500
private val MIXED_FITTING_LENGTHS = listOf(BYTE_CAPACITY, MIXED_FITTING_LENGTH)

/**
 * Checks `contentFits` against the real Rust library through [QrBridge], on the iOS simulator.
 *
 * The capacity boundaries for every mode are covered by `ContentFitsBindingsTest`; this checks the bridge mapping and
 * the ViewModel on top of the real library.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentFitNativeTest {
    private val store = ViewModelStore()

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun boundaryMapsToRightAndContentTooLong() {
        assertEquals(true, QrBridge.contentFits("7".repeat(NUMERIC_CAPACITY)).getOrNull())
        assertEquals(false, QrBridge.contentFits("7".repeat(NUMERIC_CAPACITY + 1)).getOrNull())
        assertIs<QrError.ContentTooLong>(
            QrBridge.generateQrPng("7".repeat(NUMERIC_CAPACITY + 1), QR_SIZE_PX).leftOrNull(),
        )
    }

    @Test
    fun multibyteContentIsMeasuredInBytes() {
        val fitting = "é".repeat(UTF8_CAPACITY / 2)
        assertEquals(true, QrBridge.contentFits(fitting).getOrNull())
        assertEquals(false, QrBridge.contentFits(fitting + "a").getOrNull())
    }

    @Test
    fun mixedContentThatFitsIsReportedAsFittingAndGenerates() {
        for (length in MIXED_FITTING_LENGTHS) {
            val content = mixedContent(length)
            assertEquals(true, QrBridge.contentFits(content).getOrNull(), "length $length")
            assertTrue(QrBridge.generateQrPng(content, QR_SIZE_PX).isRight(), "length $length")
        }
    }

    @Test
    fun overflowingMixedContentIsRefusedAsTooLong() {
        val content = mixedContent(MIXED_OVERFLOWING_LENGTH)
        assertEquals(false, QrBridge.contentFits(content).getOrNull())
        assertIs<QrError.ContentTooLong>(QrBridge.generateQrPng(content, QR_SIZE_PX).leftOrNull())
    }

    private fun mixedContent(length: Int) = MIXED_UNIT.repeat(length).take(length)

    @Test
    fun emptyInputIsInvalid() {
        assertIs<QrError.InvalidInput>(QrBridge.contentFits("").leftOrNull())
    }

    @Test
    fun generateScreenAcceptsWhatRustCanEncode() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val factory =
                viewModelFactory {
                    initializer { GenerateViewModel(ioDispatcher = dispatcher, qrEncoder = QrBridge) }
                }
            val viewModel = ViewModelProvider.create(store, factory)[GenerateViewModel::class]

            viewModel.onIntent(GenerateQRCodeScreenIntent.UpdateText("7".repeat(NUMERIC_CAPACITY)))
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canGenerate)

            viewModel.onIntent(GenerateQRCodeScreenIntent.UpdateText("7".repeat(NUMERIC_CAPACITY + 1)))
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isInputTooLong)
        }
}
