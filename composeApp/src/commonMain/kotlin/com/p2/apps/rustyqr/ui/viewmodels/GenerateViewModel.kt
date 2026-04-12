package com.p2.apps.rustyqr.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2.apps.rustyqr.bridge.QrBridge
import com.p2.apps.rustyqr.bridge.saveQrImage
import com.p2.apps.rustyqr.bridge.shareQrImage
import com.p2.apps.rustyqr.ui.UiText
import com.p2.apps.rustyqr.ui.mvi.AnimationPhase
import com.p2.apps.rustyqr.ui.mvi.GenerateQRCodeScreenIntent
import com.p2.apps.rustyqr.ui.mvi.GenerateScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.generate_error_too_long
import rustyqr.composeapp.generated.resources.generate_message_saved

private const val MAX_QR_INPUT_LENGTH = 4296
private const val QR_IMAGE_SIZE_PX = 512
private const val SHARE_FILE_PREFIX = "rusty-qr"
private const val KEY_INPUT_TEXT = "generate.inputText"

/**
 * Processes [GenerateQRCodeScreenIntent]s and updates [GenerateScreenState].
 *
 * Input text survives process death via [SavedStateHandle].
 */
class GenerateViewModel(
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val state: StateFlow<GenerateScreenState>
        field =
        MutableStateFlow(
            GenerateScreenState(inputText = savedStateHandle[KEY_INPUT_TEXT] ?: ""),
        )

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
        val input = state.value.inputText.trim()

        if (input.isBlank()) return

        if (input.length > MAX_QR_INPUT_LENGTH) {
            state.update {
                it.copy(error = UiText.Res(Res.string.generate_error_too_long))
            }
            return
        }

        viewModelScope.launch {
            state.update { it.copy(isGenerating = true, error = null) }
            val result =
                withContext(Dispatchers.IO) {
                    QrBridge.generateQrPng(input, QR_IMAGE_SIZE_PX)
                }
            result.fold(
                ifLeft = { error ->
                    state.update {
                        it.copy(
                            isGenerating = false,
                            error = UiText.Raw(error.reason),
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

    private fun handleShare() {
        val bytes = state.value.qrImageBytes ?: return
        viewModelScope.launch {
            shareQrImage(bytes, suggestedName()).fold(
                ifLeft = { reason -> state.update { it.copy(error = UiText.Raw(reason)) } },
                ifRight = { /* system share sheet now owns UI */ },
            )
        }
    }

    private fun handleSave() {
        val bytes = state.value.qrImageBytes ?: return
        viewModelScope.launch {
            saveQrImage(bytes, suggestedName()).fold(
                ifLeft = { reason -> state.update { it.copy(error = UiText.Raw(reason)) } },
                ifRight = { state.update { it.copy(message = UiText.Res(Res.string.generate_message_saved)) } },
            )
        }
    }

    private fun suggestedName(): String {
        val safe =
            state.value.inputText
                .take(24)
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
        return if (safe.isBlank()) SHARE_FILE_PREFIX else "$SHARE_FILE_PREFIX-$safe"
    }
}
