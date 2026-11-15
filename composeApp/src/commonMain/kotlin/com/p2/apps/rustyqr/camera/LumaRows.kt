package com.p2.apps.rustyqr.camera

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.p2.apps.rustyqr.model.QrError

/**
 * Packs a Y plane whose rows may be padded into a tightly packed `width * height` buffer.
 *
 * Temporary fix until stride-aware decoding lands in Rust. The last row may be unpadded, so the
 * minimum valid source length is `rowStride * (height - 1) + width`.
 * Returns [src] itself (no copy) when it is already tightly packed.
 * Fails with [QrError.InvalidInput] when width or height is not positive, `rowStride < width`,
 * a size calculation overflows `Int`, or [src] is shorter than the minimum length.
 */
fun packLumaRows(
    src: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
): Either<QrError, ByteArray> =
    either {
        ensure(width > 0 && height > 0) { QrError.InvalidInput("width and height must be positive") }
        ensure(rowStride >= width) { QrError.InvalidInput("rowStride must be at least width") }
        val minLength = rowStride.toLong() * (height - 1) + width
        ensure(width.toLong() * height <= Int.MAX_VALUE && minLength <= Int.MAX_VALUE) {
            QrError.InvalidInput("frame size overflows Int")
        }
        ensure(src.size >= minLength) { QrError.InvalidInput("source buffer too short") }
        if (rowStride == width && src.size == width * height) {
            src
        } else {
            ByteArray(width * height).also { packed ->
                for (row in 0 until height) {
                    val start = row * rowStride
                    src.copyInto(packed, destinationOffset = row * width, startIndex = start, endIndex = start + width)
                }
            }
        }
    }
