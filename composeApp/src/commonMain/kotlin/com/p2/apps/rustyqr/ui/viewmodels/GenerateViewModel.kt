package com.p2.apps.rustyqr.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2.apps.rustyqr.bridge.QrBridge
import com.p2.apps.rustyqr.bridge.QrEncoder
import com.p2.apps.rustyqr.bridge.saveQrImage
import com.p2.apps.rustyqr.bridge.shareQrImage
import com.p2.apps.rustyqr.model.QrError
import com.p2.apps.rustyqr.ui.UiText
import com.p2.apps.rustyqr.ui.mvi.AnimationPhase
import com.p2.apps.rustyqr.ui.mvi.ContentFit
import com.p2.apps.rustyqr.ui.mvi.GenerateQRCodeScreenIntent
import com.p2.apps.rustyqr.ui.mvi.GenerateScreenState
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
import rustyqr.composeapp.generated.resources.generate_error_too_long
import rustyqr.composeapp.generated.resources.generate_message_saved

private const val QR_IMAGE_SIZE_PX = 512
private const val SHARE_FILE_PREFIX = "rusty-qr"
private const val SHARE_FILE_NAME_MAX_INPUT_CHARS = 24
internal const val KEY_INPUT_TEXT = "generate.inputText"

private val tooLongText = UiText.Res(Res.string.generate_error_too_long)

/** Oversized content has a localised message; other errors show their reason. */
internal fun QrError.toGenerateErrorText(): UiText =
    when (this) {
        is QrError.ContentTooLong -> tooLongText

        is QrError.InvalidInput,
        is QrError.EncodingFailed,
        is QrError.DecodingFailed,
        is QrError.ImageError,
        -> UiText.Raw(reason)
    }

/**
 * Processes [GenerateQRCodeScreenIntent]s and updates [GenerateScreenState].
 *
 * Input text survives process death via [SavedStateHandle]. Blocking bridge work (FFI, gallery and share
 * I/O) runs on [ioDispatcher].
 *
 * Whether the text fits in a QR code is Rust's decision. Each distinct trimmed text is checked through
 * [qrEncoder], and a newer text cancels the check for an older one. The check is advisory and fails open: while
 * it is pending or after it fails, Generate stays enabled and generation reports `ContentTooLong` itself.
 */
class GenerateViewModel(
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val qrEncoder: QrEncoder = QrBridge,
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
                savedStateHandle[KEY_INPUT_TEXT] = intent.text
                state.update { it.copy(inputText = intent.text, error = null) }
            }

            is GenerateQRCodeScreenIntent.Generate -> handleGenerate()

            is GenerateQRCodeScreenIntent.ClearResult -> {
                state.update {
                    it.copy(
                        qrImageBytes = null,
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
                            qrImageBytes = bytes,
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
                    ifLeft = { state.update { s -> s.copy(contentFit = null) } },
                    ifRight = { fits -> state.update { it.copy(contentFit = ContentFit(content, fits)) } },
                )
        }
    }

    private fun handleShare() {
        val bytes = state.value.qrImageBytes ?: return
        val fileName = suggestedName()
        viewModelScope.launch {
            withContext(ioDispatcher) { shareQrImage(bytes, fileName) }.fold(
                ifLeft = { reason -> state.update { it.copy(error = UiText.Raw(reason)) } },
                ifRight = { /* system share sheet now owns UI */ },
            )
        }
    }

    private fun handleSave() {
        val bytes = state.value.qrImageBytes ?: return
        val fileName = suggestedName()
        viewModelScope.launch {
            withContext(ioDispatcher) { saveQrImage(bytes, fileName) }.fold(
                ifLeft = { reason -> state.update { it.copy(error = UiText.Raw(reason)) } },
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
