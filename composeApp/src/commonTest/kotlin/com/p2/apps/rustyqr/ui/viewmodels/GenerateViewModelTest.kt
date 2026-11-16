package com.p2.apps.rustyqr.ui.viewmodels

import com.p2.apps.rustyqr.ui.UiText
import com.p2.apps.rustyqr.ui.mvi.GenerateQRCodeScreenIntent
import com.p2.apps.rustyqr.ui.mvi.MAX_QR_INPUT_LENGTH
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.generate_error_too_long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GenerateViewModelTest {
    @Test
    fun overLimitInputSetsTooLongErrorWithoutGenerating() {
        val viewModel = GenerateViewModel()

        viewModel.onIntent(GenerateQRCodeScreenIntent.UpdateText("a".repeat(MAX_QR_INPUT_LENGTH + 1)))
        viewModel.onIntent(GenerateQRCodeScreenIntent.Generate)

        val state = viewModel.state.value
        assertEquals(UiText.Res(Res.string.generate_error_too_long), state.error)
        assertFalse(state.isGenerating)
        assertNull(state.qrImageBytes)
    }
}
