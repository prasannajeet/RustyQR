package com.p2.apps.rustyqr.bridge

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

/** The iOS pasteboard has no clip label, so [label] is ignored. */
@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClipEntry(
    label: String,
    text: String,
): ClipEntry = ClipEntry.withPlainText(text)
