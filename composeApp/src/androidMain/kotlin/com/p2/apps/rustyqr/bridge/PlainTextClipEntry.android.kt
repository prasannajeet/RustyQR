package com.p2.apps.rustyqr.bridge

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

private const val CLIP_LABEL = "QR content"

actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(ClipData.newPlainText(CLIP_LABEL, text))
