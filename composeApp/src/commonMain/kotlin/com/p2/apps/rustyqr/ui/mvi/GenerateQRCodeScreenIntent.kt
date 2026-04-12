package com.p2.apps.rustyqr.ui.mvi

/**
 * User intents for the Generate screen.
 */
sealed interface GenerateQRCodeScreenIntent {
    data class UpdateText(
        val text: String,
    ) : GenerateQRCodeScreenIntent

    data object Generate : GenerateQRCodeScreenIntent

    data object ClearResult : GenerateQRCodeScreenIntent

    data object ClearError : GenerateQRCodeScreenIntent

    data object ShareQr : GenerateQRCodeScreenIntent

    data object SaveQr : GenerateQRCodeScreenIntent

    data object ClearMessage : GenerateQRCodeScreenIntent
}
