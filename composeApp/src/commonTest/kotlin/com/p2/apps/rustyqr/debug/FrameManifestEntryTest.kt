package com.p2.apps.rustyqr.debug

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameManifestEntryTest {
    private fun entry(content: String?) =
        FrameManifestEntry(
            file = "android-640x480-s704-7.y",
            platform = "android",
            layout = FrameLayout(width = 640, height = 480, rowStride = 704),
            timestampMs = 123456,
            content = content,
        )

    @Test
    fun formatsDecodedFrame() {
        assertEquals(
            "{\"file\":\"android-640x480-s704-7.y\",\"platform\":\"android\",\"width\":640,\"height\":480," +
                "\"row_stride\":704,\"timestamp_ms\":123456,\"decoded\":true,\"content\":\"https://example.com\"}",
            entry("https://example.com").toJsonLine(),
        )
    }

    @Test
    fun formatsUndecodedFrameWithNullContent() {
        assertEquals(
            "{\"file\":\"android-640x480-s704-7.y\",\"platform\":\"android\",\"width\":640,\"height\":480," +
                "\"row_stride\":704,\"timestamp_ms\":123456,\"decoded\":false,\"content\":null}",
            entry(null).toJsonLine(),
        )
    }

    @Test
    fun escapesQuotesAndBackslashes() {
        assertContent("\"a\\\\b\\\"c\"", "a\\b\"c")
    }

    @Test
    fun escapesControlCharacters() {
        assertContent("\"\\n\\r\\t\\u0000\\u0001\\u001f\"", "\n\r\t\u0000\u0001\u001f")
    }

    @Test
    fun keepsNonAsciiAndSpaceUnescaped() {
        assertContent("\"héllo 世界\"", "héllo 世界")
    }

    @Test
    fun outputIsASingleLine() {
        val line = entry("a\nb\r\nc").toJsonLine()
        assertEquals(false, line.contains('\n') || line.contains('\r'))
    }

    private fun assertContent(
        expectedJson: String,
        content: String,
    ) {
        val line = entry(content).toJsonLine()
        assertEquals(true, line.endsWith(",\"content\":$expectedJson}"), line)
    }
}
