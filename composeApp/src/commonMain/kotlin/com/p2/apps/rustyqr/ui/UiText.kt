package com.p2.apps.rustyqr.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Platform-agnostic text holder used by ViewModels to emit user-visible strings
 * without depending on a Composable context. Resolved at render time by the UI.
 */
sealed interface UiText {
    data class Raw(
        val value: String,
    ) : UiText

    data class Res(
        val id: StringResource,
    ) : UiText
}

@Composable
fun UiText.resolve(): String =
    when (this) {
        is UiText.Raw -> value
        is UiText.Res -> stringResource(id)
    }
