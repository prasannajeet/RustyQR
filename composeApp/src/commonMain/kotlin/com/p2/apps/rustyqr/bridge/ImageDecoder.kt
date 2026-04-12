package com.p2.apps.rustyqr.bridge

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes a PNG [ByteArray] to [ImageBitmap] for display in Compose.
 *
 * Android: uses [android.graphics.BitmapFactory].
 * iOS (Phase 7): uses [org.jetbrains.skia.Image.makeFromEncoded].
 */
expect fun ByteArray.toImageBitmap(): ImageBitmap
