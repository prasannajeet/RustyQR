package com.p2.apps.rustyqr.ui.mvi

import com.p2.apps.rustyqr.ui.UiText

/** Maximum number of characters a QR code can encode in this app. */
const val MAX_QR_INPUT_LENGTH = 4296

/**
 * Whether [text] exceeds [MAX_QR_INPUT_LENGTH] once surrounding whitespace is trimmed.
 *
 * Generation encodes the trimmed text, so the limit applies to the trimmed length everywhere.
 */
fun isQrInputTooLong(text: String): Boolean = text.trim().length > MAX_QR_INPUT_LENGTH

/**
 * Animation phase for the Generate screen.
 */
enum class AnimationPhase {
    /** Initial state — input field and generate button visible. */
    Input,

    /** Transition in progress — input animating down, card fading in. */
    Animating,

    /** QR card fully shown, input collapsed at bottom. */
    ShowingResult,
}

/**
 * Immutable state for the Generate screen.
 */
data class GenerateScreenState(
    val inputText: String = "",
    val qrImageBytes: ByteArray? = null,
    val isGenerating: Boolean = false,
    val animationPhase: AnimationPhase = AnimationPhase.Input,
    val error: UiText? = null,
    val message: UiText? = null,
) {
    /** Whether the trimmed input exceeds [MAX_QR_INPUT_LENGTH]. */
    val isInputTooLong: Boolean get() = isQrInputTooLong(inputText)

    /** Whether the Generate action is available for the current input. */
    val canGenerate: Boolean get() = inputText.isNotBlank() && !isInputTooLong

    override fun equals(other: Any?): Boolean = this === other || (other is GenerateScreenState && sameContentAs(other))

    private fun sameContentAs(other: GenerateScreenState): Boolean =
        inputText == other.inputText &&
            qrImageBytes.contentEquals(other.qrImageBytes) &&
            isGenerating == other.isGenerating &&
            animationPhase == other.animationPhase &&
            error == other.error &&
            message == other.message

    override fun hashCode(): Int {
        var result = inputText.hashCode()
        result = 31 * result + (qrImageBytes?.contentHashCode() ?: 0)
        result = 31 * result + isGenerating.hashCode()
        result = 31 * result + animationPhase.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        result = 31 * result + (message?.hashCode() ?: 0)
        return result
    }
}
