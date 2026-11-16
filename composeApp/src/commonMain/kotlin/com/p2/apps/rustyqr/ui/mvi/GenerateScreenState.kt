package com.p2.apps.rustyqr.ui.mvi

import com.p2.apps.rustyqr.ui.UiText

/**
 * Rust's verdict on whether [content] fits in a QR code.
 *
 * [content] is the exact trimmed text the verdict was computed for, so a verdict for older text is recognisable
 * and never applies to newer text.
 */
data class ContentFit(
    val content: String,
    val fits: Boolean,
)

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
    val contentFit: ContentFit? = null,
) {
    /** The text that is encoded: the input without surrounding whitespace. */
    val contentToEncode: String get() = inputText.trim()

    /**
     * Whether Rust has said the current text does not fit.
     *
     * A missing or stale verdict never blocks, so the check fails open; generation still reports
     * `ContentTooLong` authoritatively.
     */
    val isInputTooLong: Boolean get() = contentFit == ContentFit(contentToEncode, fits = false)

    /** Whether the Generate action is available for the current input. */
    val canGenerate: Boolean get() = inputText.isNotBlank() && !isInputTooLong

    override fun equals(other: Any?): Boolean = this === other || (other is GenerateScreenState && sameContentAs(other))

    private fun sameContentAs(other: GenerateScreenState): Boolean =
        inputText == other.inputText &&
            qrImageBytes.contentEquals(other.qrImageBytes) &&
            isGenerating == other.isGenerating &&
            animationPhase == other.animationPhase &&
            error == other.error &&
            message == other.message &&
            contentFit == other.contentFit

    override fun hashCode(): Int {
        var result = inputText.hashCode()
        result = 31 * result + (qrImageBytes?.contentHashCode() ?: 0)
        result = 31 * result + isGenerating.hashCode()
        result = 31 * result + animationPhase.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        result = 31 * result + (message?.hashCode() ?: 0)
        result = 31 * result + (contentFit?.hashCode() ?: 0)
        return result
    }
}
