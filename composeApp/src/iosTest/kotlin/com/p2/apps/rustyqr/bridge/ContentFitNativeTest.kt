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

// Non-ASCII content is UTF-8 behind a 12-bit ECI header: the byte capacity minus 1.5 bytes.
private const val UTF8_CAPACITY = 2330

private const val QR_SIZE_PX = 64
private const val MIXED_UNIT = "id=0123456789012345678901234567890123456789;"
private val MIXED_CONTENT_LENGTHS = listOf(BYTE_CAPACITY, 3000, 3500, 4000, 4500)

/**
 * Checks `contentFits` against the real Rust library through [QrBridge], on the iOS simulator.
 *
 * The capacity boundaries for every mode are covered by `BindingsSmokeTest`; this checks the bridge mapping and the
 * ViewModel on top of the real library.
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
    fun contentFitsAgreesWithGeneration() {
        for (length in MIXED_CONTENT_LENGTHS) {
            val content = MIXED_UNIT.repeat(length).take(length)
            val generated = QrBridge.generateQrPng(content, QR_SIZE_PX)
            if (QrBridge.contentFits(content).getOrNull() == true) {
                assertTrue(generated.isRight(), "length $length")
            } else {
                assertIs<QrError.ContentTooLong>(generated.leftOrNull(), "length $length")
            }
        }
    }

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
