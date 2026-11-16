package com.p2.apps.rustyqr.debug

import arrow.core.Either
import com.p2.apps.rustyqr.bridge.FrameFiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameRecorderTest {
    private class FakeFiles(
        private val failWrites: Boolean = false,
    ) : FrameFiles {
        val written = mutableMapOf<String, ByteArray>()
        val lines = mutableListOf<String>()

        override fun writeBytes(
            name: String,
            bytes: ByteArray,
        ): Either<String, Unit> =
            if (failWrites) {
                Either.Left("disk full")
            } else {
                written[name] = bytes
                Either.Right(Unit)
            }

        override fun appendLine(
            name: String,
            line: String,
        ): Either<String, Unit> {
            lines += "$name:$line"
            return Either.Right(Unit)
        }
    }

    @Test
    fun disabledRecorderNeverTouchesFiles() {
        var created = false
        val recorder =
            FrameRecorder(
                platform = "android",
                files = lazy { FakeFiles().also { created = true } },
                isEnabled = false,
            )
        repeat(100) { recorder.record(ByteArray(4), 2, 2, 2, "x") }
        assertFalse(created)
    }

    @Test
    fun enabledRecorderWritesPaddedBytesAndManifestLine() {
        val files = FakeFiles()
        val recorder = FrameRecorder("android", lazy { files }, isEnabled = true, clockMillis = { 99L })
        val luma = byteArrayOf(1, 2, 9, 3, 4)
        recorder.record(luma, width = 2, height = 2, rowStride = 3, content = "hi")
        assertContentEquals(luma, files.written["android-2x2-s3-1.y"])
        assertEquals(
            listOf(
                "manifest.jsonl:{\"file\":\"android-2x2-s3-1.y\",\"platform\":\"android\",\"width\":2,\"height\":2," +
                    "\"row_stride\":3,\"timestamp_ms\":99,\"decoded\":true,\"content\":\"hi\"}",
            ),
            files.lines,
        )
    }

    @Test
    fun skipsFramesThePolicyRejects() {
        val files = FakeFiles()
        val recorder = FrameRecorder("ios", lazy { files }, isEnabled = true)
        repeat(14) { recorder.record(ByteArray(1), 1, 1, 1, null) }
        assertTrue(files.written.isEmpty())
        recorder.record(ByteArray(1), 1, 1, 1, null)
        assertEquals(setOf("ios-1x1-s1-15.y"), files.written.keys)
    }

    @Test
    fun failedWriteLeavesNoManifestLine() {
        val files = FakeFiles(failWrites = true)
        FrameRecorder("ios", lazy { files }, isEnabled = true).record(ByteArray(1), 1, 1, 1, "x")
        assertTrue(files.lines.isEmpty())
    }
}
