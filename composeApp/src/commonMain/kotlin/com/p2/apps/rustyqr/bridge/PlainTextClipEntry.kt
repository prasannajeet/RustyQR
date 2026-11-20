package com.p2.apps.rustyqr.bridge

import androidx.compose.ui.platform.ClipEntry

/**
 * Builds a [ClipEntry] holding [text] as plain text, ready for `LocalClipboard.setClipEntry`.
 *
 * [label] is a user-visible description of the clip; resolve it from resources before calling.
 * The clip payload type differs per platform (`ClipData` on Android, the pasteboard on iOS).
 */
expect fun plainTextClipEntry(
    label: String,
    text: String,
): ClipEntry
