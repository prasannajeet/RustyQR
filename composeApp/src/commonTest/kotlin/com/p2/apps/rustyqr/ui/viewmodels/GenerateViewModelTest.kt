package com.p2.apps.rustyqr.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.p2.apps.rustyqr.bridge.QrEncoder
import com.p2.apps.rustyqr.model.QrError
import com.p2.apps.rustyqr.ui.UiText
import com.p2.apps.rustyqr.ui.mvi.GenerateQRCodeScreenIntent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.generate_error_failed
import rustyqr.composeapp.generated.resources.generate_error_save_failed
import rustyqr.composeapp.generated.resources.generate_error_share_failed
import rustyqr.composeapp.generated.resources.generate_error_too_long
import rustyqr.composeapp.generated.resources.generate_message_saved
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NUMERIC_CAPACITY_LEVEL_M = 5596
private val tooLongError = UiText.Res(Res.string.generate_error_too_long)
private val failedError = UiText.Res(Res.string.generate_error_failed)
private val saveFailedError = UiText.Res(Res.string.generate_error_save_failed)
private val shareFailedError = UiText.Res(Res.string.generate_error_share_failed)

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
        store.clear()
        Dispatchers.resetMain()
    }

    private val store = ViewModelStore()
    private val logged = mutableListOf<String>()

    /** The calls one image lambda received, each as the bytes and the suggested name. */
    private class ImageCalls {
        val calls = mutableListOf<Pair<List<Byte>, String>>()

        fun record(
            bytes: ByteArray,
            name: String,
            result: Either<String, Unit>,
        ): Either<String, Unit> {
            calls += bytes.toList() to name
            return result
        }
    }

    private val savedImages = ImageCalls()
    private val sharedImages = ImageCalls()
    private var saveResult: Either<String, Unit> = Unit.right()
    private var shareResult: Either<String, Unit> = Unit.right()

    private fun viewModel(
        encoder: QrEncoder,
        handle: SavedStateHandle = SavedStateHandle(),
        ioDispatcher: CoroutineDispatcher = dispatcher,
    ): GenerateViewModel {
        val factory =
            viewModelFactory {
                initializer {
                    GenerateViewModel(
                        handle,
                        ioDispatcher,
                        encoder,
                        log = { logged += it },
                        saveImage = { bytes, name -> savedImages.record(bytes, name, saveResult) },
                        shareImage = { bytes, name -> sharedImages.record(bytes, name, shareResult) },
                    )
                }
            }
        return ViewModelProvider.create(store, factory)[GenerateViewModel::class]
    }

    private fun GenerateViewModel.type(text: String) = onIntent(GenerateQRCodeScreenIntent.UpdateText(text))

    @Test
    fun rustFitsAboveOldByteLimitCanGenerate() =
        runTest(dispatcher) {
            val encoder = FakeQrEncoder()
            val viewModel = viewModel(encoder)
            val text = "7".repeat(NUMERIC_CAPACITY_LEVEL_M)

            viewModel.type(text)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canGenerate)
            viewModel.onIntent(GenerateQRCodeScreenIntent.Generate)
            advanceUntilIdle()

            assertEquals(listOf(text), encoder.checked)
            assertEquals(listOf(text), encoder.generated)
            assertNotNull(viewModel.state.value.qrPng)
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
            val ioScheduler = TestCoroutineScheduler()
            val encoder = FakeQrEncoder(fits = { false.right() })
            val viewModel = viewModel(encoder, ioDispatcher = StandardTestDispatcher(ioScheduler))

            viewModel.type("abc")
            advanceUntilIdle()
            assertTrue(encoder.checked.isEmpty())
            assertFalse(viewModel.state.value.isInputTooLong)
            assertTrue(viewModel.state.value.canGenerate)

            ioScheduler.advanceUntilIdle()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isInputTooLong)
        }

    @Test
    fun newerTextCancelsTheInFlightCheck() =
        runTest(dispatcher) {
            val ioScheduler = TestCoroutineScheduler()
            val encoder = FakeQrEncoder()
            val viewModel = viewModel(encoder, ioDispatcher = StandardTestDispatcher(ioScheduler))

            viewModel.type("a")
            advanceUntilIdle()
            viewModel.type("ab")
            advanceUntilIdle()
            ioScheduler.advanceUntilIdle()
            advanceUntilIdle()
            ioScheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertEquals(listOf("ab"), encoder.checked)
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
            val viewModel = viewModel(encoder, SavedStateHandle(mapOf(KEY_INPUT_TEXT to "x")))

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
            val png = viewModel.state.value.qrPng
            assertContentEquals(byteArrayOf(1), png?.bytes)
        }

    @Test
    fun failedCheckClearsAStaleVerdict() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    FakeQrEncoder(fits = { if (it == "abc") false.right() else QrError.EncodingFailed("boom").left() }),
                )

            viewModel.type("abc")
            advanceUntilIdle()
            assertTrue(viewModel.state.value.showsTooLongHint)
            viewModel.type("abcd")
            advanceUntilIdle()

            assertFalse(viewModel.state.value.showsTooLongHint)
            assertNull(viewModel.state.value.contentFit)
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
            assertNull(viewModel.state.value.qrPng)
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
    fun otherErrorsMapToTheLocalisedFailureText() {
        val errors =
            listOf(
                QrError.InvalidInput("boom"),
                QrError.EncodingFailed("boom"),
                QrError.DecodingFailed("boom"),
                QrError.ImageError("boom"),
            )
        errors.forEach { assertEquals(failedError, it.toGenerateErrorText()) }
    }

    @Test
    fun generationFailureShowsLocalisedTextAndLogsTheRustReason() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeQrEncoder(png = QrError.EncodingFailed("rust says boom").left()))

            viewModel.type("abc")
            advanceUntilIdle()
            viewModel.onIntent(GenerateQRCodeScreenIntent.Generate)
            advanceUntilIdle()

            assertEquals(failedError, viewModel.state.value.error)
            assertEquals(listOf("Generate failed: rust says boom"), logged)
        }

    @Test
    fun hugeInputIsKeptInStateButOnlyACappedCopyIsSaved() =
        runTest(dispatcher) {
            val handle = SavedStateHandle()
            val viewModel = viewModel(FakeQrEncoder(), handle)
            val huge = "x".repeat(MAX_SAVED_INPUT_CHARS + 100)

            viewModel.type(huge)

            assertEquals(huge, viewModel.state.value.inputText)
            assertEquals(MAX_SAVED_INPUT_CHARS, handle.get<String>(KEY_INPUT_TEXT)?.length)
        }

    @Test
    fun inputWithinTheCapIsSavedUnchanged() =
        runTest(dispatcher) {
            val handle = SavedStateHandle()
            val viewModel = viewModel(FakeQrEncoder(), handle)
            val text = "7".repeat(NUMERIC_CAPACITY_LEVEL_M)

            viewModel.type(text)

            assertEquals(text, handle.get<String>(KEY_INPUT_TEXT))
        }

    private fun TestScope.generatedViewModel(): GenerateViewModel {
        val viewModel = viewModel(FakeQrEncoder())
        viewModel.type("abc")
        advanceUntilIdle()
        viewModel.onIntent(GenerateQRCodeScreenIntent.Generate)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun saveCallsOnlyTheSaveLambdaWithThePngAndTheSuggestedName() =
        runTest(dispatcher) {
            val viewModel = generatedViewModel()

            viewModel.onIntent(GenerateQRCodeScreenIntent.SaveQr)
            advanceUntilIdle()

            assertEquals(listOf(listOf<Byte>(1) to "rusty-qr-abc"), savedImages.calls)
            assertTrue(sharedImages.calls.isEmpty())
            assertEquals(UiText.Res(Res.string.generate_message_saved), viewModel.state.value.message)
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun shareCallsOnlyTheShareLambdaWithThePngAndTheSuggestedName() =
        runTest(dispatcher) {
            val viewModel = generatedViewModel()

            viewModel.onIntent(GenerateQRCodeScreenIntent.ShareQr)
            advanceUntilIdle()

            assertEquals(listOf(listOf<Byte>(1) to "rusty-qr-abc"), sharedImages.calls)
            assertTrue(savedImages.calls.isEmpty())
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun saveFailureShowsLocalisedTextAndLogsThePlatformReason() =
        runTest(dispatcher) {
            saveResult = "disk full".left()
            val viewModel = generatedViewModel()

            viewModel.onIntent(GenerateQRCodeScreenIntent.SaveQr)
            advanceUntilIdle()

            assertEquals(saveFailedError, viewModel.state.value.error)
            assertEquals(listOf("Save failed: disk full"), logged)
        }

    @Test
    fun shareFailureShowsLocalisedTextAndLogsThePlatformReason() =
        runTest(dispatcher) {
            shareResult = "no share target".left()
            val viewModel = generatedViewModel()

            viewModel.onIntent(GenerateQRCodeScreenIntent.ShareQr)
            advanceUntilIdle()

            assertEquals(shareFailedError, viewModel.state.value.error)
            assertEquals(listOf("Share failed: no share target"), logged)
        }

    @Test
    fun failedContentFitCheckIsLogged() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeQrEncoder(fits = { QrError.EncodingFailed("check boom").left() }))

            viewModel.type("abc")
            advanceUntilIdle()

            assertEquals(listOf("Fit check failed: check boom"), logged)
        }
}
