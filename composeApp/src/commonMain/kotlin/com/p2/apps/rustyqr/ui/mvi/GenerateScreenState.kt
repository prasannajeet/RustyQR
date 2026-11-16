package com.p2.apps.rustyqr.ui.mvi

import com.p2.apps.rustyqr.ui.UiText

/**
 * Maximum size, in UTF-8 bytes, of the trimmed text this app accepts for a QR code.
 *
 * The largest QR code (version 40) at error-correction level M, the level `generate_png` uses, holds 2331 bytes
 * in byte mode, so any text whose trimmed UTF-8 encoding fits always encodes. Digits-only or uppercase-only text
 * could hold more, but is held to the same simple rule. Rust stays the source of truth: its
 * `MAX_BYTE_CONTENT_LEN_LEVEL_M` test pins this number.
 */
const val MAX_QR_INPUT_BYTES = 2331

/**
 * Whether [text] exceeds [MAX_QR_INPUT_BYTES] once surrounding whitespace is trimmed.
 *
 * Generation encodes the trimmed text, so the limit applies to its UTF-8 byte size everywhere.
 */
fun isQrInputTooLong(text: String): Boolean = text.trim().encodeToByteArray().size > MAX_QR_INPUT_BYTES

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
    /** Whether the trimmed input exceeds [MAX_QR_INPUT_BYTES]. */
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
