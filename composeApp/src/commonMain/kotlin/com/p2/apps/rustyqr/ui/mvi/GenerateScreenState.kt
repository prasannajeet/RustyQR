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
 * PNG bytes of a generated QR code. An array compares by identity, so this wrapper compares by content: the state
 * stays a plain data class and [kotlinx.coroutines.flow.MutableStateFlow] drops only genuinely identical updates.
 */
class QrPng(
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other || (other is QrPng && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "QrPng(${bytes.size} bytes)"
}

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
    val qrPng: QrPng? = null,
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

    /**
     * Whether the too-long hint shows: the latest verdict says the text did not fit and some text remains.
     *
     * Unlike [isInputTooLong] this ignores which text the verdict was for, so the hint stays put while the
     * user edits over-limit text and the next check is pending. Generation is still gated by [canGenerate].
     */
    val showsTooLongHint: Boolean get() = contentFit?.fits == false && contentToEncode.isNotEmpty()

    /** Whether the Generate action is available for the current input. */
    val canGenerate: Boolean get() = contentToEncode.isNotEmpty() && !isInputTooLong
}
