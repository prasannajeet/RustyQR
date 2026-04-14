package com.p2.apps.rustyqr.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2.apps.rustyqr.ui.components.AmberButton
import com.p2.apps.rustyqr.ui.components.AnimatedGenerateContent
import com.p2.apps.rustyqr.ui.components.QrResultCard
import com.p2.apps.rustyqr.ui.mvi.AnimationPhase
import com.p2.apps.rustyqr.ui.mvi.GenerateQRCodeScreenIntent
import com.p2.apps.rustyqr.ui.mvi.GenerateScreenState
import com.p2.apps.rustyqr.ui.resolve
import com.p2.apps.rustyqr.ui.viewmodels.GenerateViewModel
import org.jetbrains.compose.resources.stringResource
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.generate_button
import rustyqr.composeapp.generated.resources.generate_error_too_long
import rustyqr.composeapp.generated.resources.generate_input_hint
import rustyqr.composeapp.generated.resources.generate_input_placeholder
import rustyqr.composeapp.generated.resources.generate_loading
import rustyqr.composeapp.generated.resources.generate_preview_format

private const val MAX_QR_VISIBLE_LENGTH = 4296

/**
 * Generate screen — input field + QR generation with animated card result.
 *
 * Wires [GenerateViewModel] to [GenerateContent].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GenerateScreen(viewModel: GenerateViewModel = viewModel { GenerateViewModel(createSavedStateHandle()) }) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Hardware/gesture back collapses result card back to input (instead of exiting app)
    BackHandler(enabled = state.animationPhase == AnimationPhase.ShowingResult) {
        viewModel.onIntent(GenerateQRCodeScreenIntent.ClearResult)
    }

    val errorText = state.error?.resolve()
    val messageText = state.message?.resolve()

    // Show error snackbar
    LaunchedEffect(errorText) {
        errorText?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.onIntent(GenerateQRCodeScreenIntent.ClearError)
        }
    }

    // Show success/info snackbar (e.g. "Saved to gallery")
    LaunchedEffect(messageText) {
        messageText?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.onIntent(GenerateQRCodeScreenIntent.ClearMessage)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GenerateContent(
            state = state,
            onIntent = viewModel::onIntent,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .imePadding(),
            snackbar = { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }
}

@Composable
private fun GenerateContent(
    state: GenerateScreenState,
    onIntent: (GenerateQRCodeScreenIntent) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        AnimatedGenerateContent(
            animationPhase = state.animationPhase,
            inputContent = {
                InputForm(state = state, onIntent = onIntent)
            },
            resultContent = {
                if (state.qrImageBytes != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        QrResultCard(
                            qrImageBytes = state.qrImageBytes,
                            modifier = Modifier.fillMaxWidth(),
                            onClose = { onIntent(GenerateQRCodeScreenIntent.ClearResult) },
                            onShare = { onIntent(GenerateQRCodeScreenIntent.ShareQr) },
                            onSave = { onIntent(GenerateQRCodeScreenIntent.SaveQr) },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        CompactInputPill(
                            text = state.inputText,
                            onClearResult = { onIntent(GenerateQRCodeScreenIntent.ClearResult) },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun InputForm(
    state: GenerateScreenState,
    onIntent: (GenerateQRCodeScreenIntent) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .imePadding(),
    ) {
        Text(
            text = stringResource(Res.string.generate_input_hint),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        val isInputTooLong = state.inputText.length > MAX_QR_VISIBLE_LENGTH

        OutlinedTextField(
            value = state.inputText,
            onValueChange = { onIntent(GenerateQRCodeScreenIntent.UpdateText(it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(Res.string.generate_input_placeholder),
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                ),
            shape = MaterialTheme.shapes.medium,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                ),
            isError = isInputTooLong,
            supportingText =
                if (isInputTooLong) {
                    {
                        Text(
                            stringResource(Res.string.generate_error_too_long),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    null
                },
            minLines = 3,
        )

        Spacer(modifier = Modifier.height(16.dp))

        AmberButton(
            text =
                stringResource(
                    if (state.isGenerating) Res.string.generate_loading else Res.string.generate_button,
                ),
            onClick = {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                onIntent(GenerateQRCodeScreenIntent.Generate)
            },
            enabled =
                state.inputText.isNotBlank() &&
                    state.inputText.length <= MAX_QR_VISIBLE_LENGTH,
            loading = state.isGenerating,
        )
    }
}

@Composable
private fun CompactInputPill(
    text: String,
    onClearResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Brand pill shape — intentional MD3 deviation (20dp radius pill for compact input summary)
    val preview = if (text.length > 40) text.take(40) + "…" else text
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClearResult,
    ) {
        Text(
            text = stringResource(Res.string.generate_preview_format, preview),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                ),
        )
    }
}
