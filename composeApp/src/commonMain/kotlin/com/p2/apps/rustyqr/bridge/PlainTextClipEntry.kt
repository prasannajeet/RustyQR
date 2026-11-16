package com.p2.apps.rustyqr.bridge

import androidx.compose.ui.platform.ClipEntry

/**
 * Builds a [ClipEntry] holding [text] as plain text, ready for `LocalClipboard.setClipEntry`.
 *
 * The clip payload type differs per platform (`ClipData` on Android, the pasteboard on iOS).
 */
expect fun plainTextClipEntry(text: String): ClipEntry
