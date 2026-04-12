package com.p2.apps.rustyqr.ui.mvi

import com.p2.apps.rustyqr.ui.UiText

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
    @Suppress("ReturnCount")
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is GenerateScreenState) return false
        if (inputText != other.inputText) return false
        if (!qrImageBytes.contentEquals(other.qrImageBytes)) return false
        if (isGenerating != other.isGenerating) return false
        if (animationPhase != other.animationPhase) return false
        if (error != other.error) return false
        if (message != other.message) return false
        return true
    }

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
