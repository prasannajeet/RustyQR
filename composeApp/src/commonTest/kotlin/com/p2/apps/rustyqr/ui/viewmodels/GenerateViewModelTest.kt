package com.p2.apps.rustyqr.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.p2.apps.rustyqr.bridge.QrEncoder
import com.p2.apps.rustyqr.model.QrError
import com.p2.apps.rustyqr.ui.UiText
import com.p2.apps.rustyqr.ui.mvi.GenerateQRCodeScreenIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.generate_error_too_long
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ABOVE_OLD_LIMIT = 5596
private val tooLongError = UiText.Res(Res.string.generate_error_too_long)

/** A [QrEncoder] whose answers come from the test and which records every call. */
private class FakeQrEncoder(
    private val fits: (String) -> Either<QrError, Boolean> = { true.right() },
    private val png: Either<QrError, ByteArray> = byteArrayOf(1).right(),
) : QrEncoder {
    val checked = mutableListOf<String>()
    val generated = mutableListOf<String>()

    override fun generateQrPng(
        content: String,
        size: Int,
    ): Either<QrError, ByteArray> {
        generated += content
        return png
    }

    override fun contentFits(content: String): Either<QrError, Boolean> {
        checked += content
        return fits(content)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GenerateViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        encoder: QrEncoder,
        handle: SavedStateHandle = SavedStateHandle(),
    ) = GenerateViewModel(handle, dispatcher, encoder)

    private fun GenerateViewModel.type(text: String) = onIntent(GenerateQRCodeScreenIntent.UpdateText(text))

    @Test
    fun rustFitsAboveOldByteLimitCanGenerate() =
        runTest(dispatcher) {
            val encoder = FakeQrEncoder()
            val viewModel = viewModel(encoder)
            val text = "7".repeat(ABOVE_OLD_LIMIT)

            viewModel.type(text)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canGenerate)
            viewModel.onIntent(GenerateQRCodeScreenIntent.Generate)
            advanceUntilIdle()

            assertEquals(listOf(text), encoder.generated)
            assertNotNull(viewModel.state.value.qrImageBytes)
        }

    @Test
    fun rustTooLongBlocksWithLocalisedError() =
        runTest(dispatcher) {
            val encoder = FakeQrEncoder(fits = { false.right() })
            val viewModel = viewModel(encoder)

            viewModel.type("abc")
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isInputTooLong)
            viewModel.onIntent(GenerateQRCodeScreenIntent.Generate)
            advanceUntilIdle()

            assertEquals(tooLongError, viewModel.state.value.error)
            assertTrue(encoder.generated.isEmpty())
        }

    @Test
    fun pendingCheckDoesNotBlock() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeQrEncoder(fits = { false.right() }))

            viewModel.type("abc")

            assertFalse(viewModel.state.value.isInputTooLong)
            assertTrue(viewModel.state.value.canGenerate)
        }

    @Test
    fun staleVerdictDoesNotBlockNewText() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeQrEncoder(fits = { (it != "abc").right() }))

            viewModel.type("abc")
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isInputTooLong)
            viewModel.type("abcd")

            assertFalse(viewModel.state.value.isInputTooLong)
            assertTrue(viewModel.state.value.canGenerate)
        }

    @Test
    fun checksTrimmedTextAndSkipsBlank() =
        runTest(dispatcher) {
            val encoder = FakeQrEncoder()
            val viewModel = viewModel(encoder)

            advanceUntilIdle()
            viewModel.type("   ")
            advanceUntilIdle()
            viewModel.type("  abc \n")
            advanceUntilIdle()

            assertEquals(listOf("abc"), encoder.checked)
        }

    @Test
    fun typingBurstChecksOnlyLatestText() =
        runTest(dispatcher) {
            val encoder = FakeQrEncoder()
            val viewModel = viewModel(encoder)

            viewModel.type("a")
            viewModel.type("ab")
            viewModel.type("abc")
            advanceUntilIdle()

            assertEquals(listOf("abc"), encoder.checked)
        }

    @Test
    fun restoredInputIsChecked() =
        runTest(dispatcher) {
            val encoder = FakeQrEncoder(fits = { false.right() })
            val viewModel = viewModel(encoder, SavedStateHandle(mapOf("generate.inputText" to "x")))

            advanceUntilIdle()

            assertEquals(listOf("x"), encoder.checked)
            assertTrue(viewModel.state.value.isInputTooLong)
        }

    @Test
    fun checkFailureNeverBlocks() =
        runTest(dispatcher) {
            val encoder = FakeQrEncoder(fits = { QrError.EncodingFailed("boom").left() })
            val viewModel = viewModel(encoder)

            viewModel.type("abc")
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canGenerate)
            viewModel.onIntent(GenerateQRCodeScreenIntent.Generate)
            advanceUntilIdle()

            assertEquals(listOf("abc"), encoder.generated)
            assertContentEquals(byteArrayOf(1), viewModel.state.value.qrImageBytes)
        }

    @Test
    fun generationContentTooLongShowsLocalisedError() =
        runTest(dispatcher) {
            val encoder = FakeQrEncoder(png = QrError.ContentTooLong("too long").left())
            val viewModel = viewModel(encoder)

            viewModel.type("abc")
            advanceUntilIdle()
            viewModel.onIntent(GenerateQRCodeScreenIntent.Generate)
            advanceUntilIdle()

            assertEquals(tooLongError, viewModel.state.value.error)
            assertNull(viewModel.state.value.qrImageBytes)
            assertFalse(viewModel.state.value.isGenerating)
        }

    @Test
    fun contentTooLongFromRustMapsToLocalisedMessage() {
        assertEquals(
            tooLongError,
            QrError.ContentTooLong("Content too long: data too long").toGenerateErrorText(),
        )
    }

    @Test
    fun otherErrorsKeepRustReason() {
        assertEquals(UiText.Raw("boom"), QrError.EncodingFailed("boom").toGenerateErrorText())
    }
}
