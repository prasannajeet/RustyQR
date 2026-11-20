package com.p2.apps.rustyqr.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.core.Either
import com.p2.apps.rustyqr.bridge.QrBridge
import com.p2.apps.rustyqr.bridge.QrEncoder
import com.p2.apps.rustyqr.bridge.logInfo
import com.p2.apps.rustyqr.bridge.saveQrImage
import com.p2.apps.rustyqr.bridge.shareQrImage
import com.p2.apps.rustyqr.model.QrError
import com.p2.apps.rustyqr.ui.UiText
import com.p2.apps.rustyqr.ui.mvi.AnimationPhase
import com.p2.apps.rustyqr.ui.mvi.ContentFit
import com.p2.apps.rustyqr.ui.mvi.GenerateQRCodeScreenIntent
import com.p2.apps.rustyqr.ui.mvi.GenerateScreenState
import com.p2.apps.rustyqr.ui.mvi.QrPng
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.generate_error_failed
import rustyqr.composeapp.generated.resources.generate_error_save_failed
import rustyqr.composeapp.generated.resources.generate_error_share_failed
import rustyqr.composeapp.generated.resources.generate_error_too_long
import rustyqr.composeapp.generated.resources.generate_message_saved

private const val QR_IMAGE_SIZE_PX = 512
private const val SHARE_FILE_PREFIX = "rusty-qr"
private const val SHARE_FILE_NAME_MAX_INPUT_CHARS = 24
private const val LOG_TAG = "Generate"
internal const val KEY_INPUT_TEXT = "generate.inputText"

/**
 * Most input text kept in [SavedStateHandle]. Android writes saved state into a size-limited transaction when the app
 * backgrounds, so an unbounded paste would crash it. This is far above what any QR code holds (7089 digits at the
 * lowest error-correction level, 5596 at the level the app encodes at), so no input that can be encoded is cut; only
 * the restored copy of an over-limit paste is.
 */
internal const val MAX_SAVED_INPUT_CHARS = 32_768

private val tooLongText = UiText.Res(Res.string.generate_error_too_long)
private val failedText = UiText.Res(Res.string.generate_error_failed)
private val saveFailedText = UiText.Res(Res.string.generate_error_save_failed)
private val shareFailedText = UiText.Res(Res.string.generate_error_share_failed)

/** Oversized content and every other failure have a localised message; callers log the Rust reason. */
internal fun QrError.toGenerateErrorText(): UiText =
    when (this) {
        is QrError.ContentTooLong -> tooLongText

        is QrError.InvalidInput,
        is QrError.EncodingFailed,
        is QrError.DecodingFailed,
        is QrError.ImageError,
        -> failedText
    }

/**
 * Processes [GenerateQRCodeScreenIntent]s and updates [GenerateScreenState].
 *
 * Input text survives process death via [SavedStateHandle], capped at [MAX_SAVED_INPUT_CHARS]. Blocking bridge work
 * (FFI, gallery and share I/O) runs on [ioDispatcher]; [saveImage] and [shareImage] default to the platform bridge.
 * Failure reasons from Rust and the platform are English text for developers, so they go to [log] and the screen
 * shows a localised message instead.
 *
 * Whether the text fits in a QR code is Rust's decision. Each distinct trimmed text is checked through
 * [qrEncoder], and a newer text cancels the check for an older one. The check is advisory and fails open: while
 * it is pending or after it fails, Generate stays enabled and generation reports `ContentTooLong` itself.
 */
class GenerateViewModel(
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val qrEncoder: QrEncoder = QrBridge,
    private val log: (String) -> Unit = { logInfo(LOG_TAG, it) },
    private val saveImage: suspend (ByteArray, String) -> Either<String, Unit> = ::saveQrImage,
    private val shareImage: suspend (ByteArray, String) -> Either<String, Unit> = ::shareQrImage,
) : ViewModel() {
    val state: StateFlow<GenerateScreenState>
        field =
        MutableStateFlow(
            GenerateScreenState(inputText = savedStateHandle[KEY_INPUT_TEXT] ?: ""),
        )

    init {
        viewModelScope.launch {
            state.map { it.contentToEncode }.distinctUntilChanged().collectLatest(::checkContentFit)
        }
    }

    fun onIntent(intent: GenerateQRCodeScreenIntent) {
        when (intent) {
            is GenerateQRCodeScreenIntent.UpdateText -> {
                savedStateHandle[KEY_INPUT_TEXT] = intent.text.take(MAX_SAVED_INPUT_CHARS)
                state.update { it.copy(inputText = intent.text, error = null) }
            }

            is GenerateQRCodeScreenIntent.Generate -> handleGenerate()

            is GenerateQRCodeScreenIntent.ClearResult -> {
                state.update {
                    it.copy(
                        qrPng = null,
                        animationPhase = AnimationPhase.Input,
                        error = null,
                    )
                }
            }

            is GenerateQRCodeScreenIntent.ClearError -> {
                state.update { it.copy(error = null) }
            }

            is GenerateQRCodeScreenIntent.ShareQr -> handleShare()

            is GenerateQRCodeScreenIntent.SaveQr -> handleSave()

            is GenerateQRCodeScreenIntent.ClearMessage -> {
                state.update { it.copy(message = null) }
            }
        }
    }

    private fun handleGenerate() {
        val current = state.value

        if (!current.canGenerate) {
            if (current.isInputTooLong) {
                state.update { it.copy(error = tooLongText) }
            }
            return
        }

        val input = current.contentToEncode
        viewModelScope.launch {
            state.update { it.copy(isGenerating = true, error = null) }
            val result =
                withContext(ioDispatcher) {
                    qrEncoder.generateQrPng(input, QR_IMAGE_SIZE_PX)
                }
            result.fold(
                ifLeft = { error ->
                    log("Generate failed: ${error.reason}")
                    state.update {
                        it.copy(
                            isGenerating = false,
                            error = error.toGenerateErrorText(),
                        )
                    }
                },
                ifRight = { bytes ->
                    state.update {
                        it.copy(
                            qrPng = QrPng(bytes),
                            isGenerating = false,
                            animationPhase = AnimationPhase.Animating,
                        )
                    }
                    // Transition to ShowingResult after Animating kicks off
                    state.update { it.copy(animationPhase = AnimationPhase.ShowingResult) }
                },
            )
        }
    }

    /** Advisory pre-check; a Left clears the verdict, and generation reports the same error itself. */
    private suspend fun checkContentFit(content: String) {
        if (content.isNotEmpty()) {
            withContext(ioDispatcher) { qrEncoder.contentFits(content) }
                .fold(
                    ifLeft = { error ->
                        log("Fit check failed: ${error.reason}")
                        state.update { s -> s.copy(contentFit = null) }
                    },
                    ifRight = { fits -> state.update { it.copy(contentFit = ContentFit(content, fits)) } },
                )
        }
    }

    private fun handleShare() {
        val bytes = state.value.qrPng?.bytes ?: return
        val fileName = suggestedName()
        viewModelScope.launch {
            withContext(ioDispatcher) { shareImage(bytes, fileName) }.fold(
                ifLeft = { reason ->
                    log("Share failed: $reason")
                    state.update { it.copy(error = shareFailedText) }
                },
                ifRight = { /* system share sheet now owns UI */ },
            )
        }
    }

    private fun handleSave() {
        val bytes = state.value.qrPng?.bytes ?: return
        val fileName = suggestedName()
        viewModelScope.launch {
            withContext(ioDispatcher) { saveImage(bytes, fileName) }.fold(
                ifLeft = { reason ->
                    log("Save failed: $reason")
                    state.update { it.copy(error = saveFailedText) }
                },
                ifRight = { state.update { s -> s.copy(message = UiText.Res(Res.string.generate_message_saved)) } },
            )
        }
    }

    private fun suggestedName(): String {
        val safe =
            state.value.inputText
                .take(SHARE_FILE_NAME_MAX_INPUT_CHARS)
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
        return if (safe.isBlank()) SHARE_FILE_PREFIX else "$SHARE_FILE_PREFIX-$safe"
    }
}
