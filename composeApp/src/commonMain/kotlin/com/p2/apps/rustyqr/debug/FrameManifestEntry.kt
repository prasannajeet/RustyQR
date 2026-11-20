package com.p2.apps.rustyqr.debug

private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_DIGITS = 4

/** Pixel layout of a saved Y plane. [rowStride] is the number of bytes between row starts, padding included. */
data class FrameLayout(
    val width: Int,
    val height: Int,
    val rowStride: Int,
)

/** One line of `manifest.jsonl`. [content] is null when nothing decoded. */
data class FrameManifestEntry(
    val file: String,
    val platform: String,
    val layout: FrameLayout,
    val timestampMs: Long,
    val content: String?,
) {
    /** Renders this entry as one JSON object on one line, without a trailing newline. */
    fun toJsonLine(): String =
        buildString {
            append("{\"file\":").append(file.toJsonString())
            append(",\"platform\":").append(platform.toJsonString())
            append(",\"width\":").append(layout.width)
            append(",\"height\":").append(layout.height)
            append(",\"row_stride\":").append(layout.rowStride)
            append(",\"timestamp_ms\":").append(timestampMs)
            append(",\"decoded\":").append(content != null)
            append(",\"content\":").append(content?.toJsonString() ?: "null")
            append('}')
        }
}

/** Quotes and escapes the string as a JSON string: quote, backslash and control characters U+0000..U+001F. */
private fun String.toJsonString(): String =
    buildString(length + 2) {
        append('"')
        for (char in this@toJsonString) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u").append(char.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                else -> append(char)
            }
        }
        append('"')
    }
