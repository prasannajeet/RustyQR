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
        private val failList: Boolean = false,
        private val failNames: Set<String> = emptySet(),
        private val extraNames: List<String> = emptyList(),
    ) : FrameFiles {
        val written = mutableMapOf<String, ByteArray>()
        val manifest = mutableListOf<String>()

        override fun writeBytes(
            name: String,
            bytes: ByteArray,
        ): Either<String, Unit> =
            if (failWrites || name in failNames) {
                Either.Left("disk full")
            } else {
                written[name] = bytes
                Either.Right(Unit)
            }

        override fun appendLine(
            name: String,
            line: String,
        ): Either<String, Unit> {
            manifest += line
            return Either.Right(Unit)
        }

        override fun listNames(): Either<String, List<String>> =
            if (failList) Either.Left("unreadable") else Either.Right(written.keys.toList() + extraNames)
    }

    private class CountingPolicy(
        sampleInterval: Int = 1,
    ) : CapturePolicy(sampleInterval) {
        var calls = 0

        override fun accept(decoded: Boolean): Boolean {
            calls++
            return super.accept(decoded)
        }
    }

    private fun recorder(
        files: FrameFiles,
        policy: CapturePolicy = CapturePolicy(sampleInterval = 1),
        platform: String = "android",
    ) = FrameRecorder(platform, lazy { Either.Right(files) }, isEnabled = true, policy = policy, clockMillis = { 99L })

    @Test
    fun disabledRecorderNeverTouchesFilesOrPolicy() {
        var created = false
        val policy = CountingPolicy()
        val recorder =
            FrameRecorder(
                platform = "android",
                files = lazy { Either.Right(FakeFiles()).also { created = true } },
                isEnabled = false,
                policy = policy,
            )
        repeat(100) { recorder.record(ByteArray(4), 2, 2, 2, "x") }
        assertFalse(created)
        assertEquals(0, policy.calls)
    }

    @Test
    fun enabledRecorderWritesPaddedBytesAndManifestLine() {
        val files = FakeFiles()
        val luma = byteArrayOf(1, 2, 9, 3, 4)
        recorder(files).record(luma, width = 2, height = 2, rowStride = 3, content = "hi")
        assertContentEquals(luma, files.written["android-2x2-s3-1.y"])
        assertEquals(
            listOf(
                "{\"file\":\"android-2x2-s3-1.y\",\"platform\":\"android\",\"width\":2,\"height\":2," +
                    "\"row_stride\":3,\"timestamp_ms\":99,\"decoded\":true,\"content\":\"hi\"}",
            ),
            files.manifest,
        )
    }

    @Test
    fun skipsFramesThePolicyRejects() {
        val files = FakeFiles()
        val recorder = recorder(files, CapturePolicy())
        repeat(14) { recorder.record(ByteArray(1), 1, 1, 1, null) }
        assertTrue(files.written.isEmpty())
        recorder.record(ByteArray(1), 1, 1, 1, null)
        assertEquals(setOf("android-1x1-s1-1.y"), files.written.keys)
    }

    @Test
    fun laterLaunchContinuesNumberingInsteadOfOverwriting() {
        val files = FakeFiles()
        recorder(files).record(byteArrayOf(1), 1, 1, 1, "a")
        recorder(files).record(byteArrayOf(2), 1, 1, 1, "b")
        assertEquals(setOf("android-1x1-s1-1.y", "android-1x1-s1-2.y"), files.written.keys)
        assertEquals(2, files.manifest.size)
        assertContentEquals(byteArrayOf(1), files.written["android-1x1-s1-1.y"])
    }

    @Test
    fun failedWriteLeavesNoManifestLine() {
        val files = FakeFiles(failWrites = true)
        recorder(files).record(ByteArray(1), 1, 1, 1, "x")
        assertTrue(files.manifest.isEmpty())
    }

    @Test
    fun unlistableDirectorySkipsTheFrame() {
        val files = FakeFiles(failList = true)
        recorder(files).record(ByteArray(1), 1, 1, 1, "x")
        assertTrue(files.written.isEmpty())
        assertTrue(files.manifest.isEmpty())
    }

    @Test
    fun unavailableCacheDirectorySkipsTheFrame() {
        val recorder = FrameRecorder("ios", lazy { Either.Left("no caches") }, isEnabled = true)
        recorder.record(ByteArray(1), 1, 1, 1, "x")
    }

    @Test
    fun gapFromFailedSaveDoesNotLetALaterLaunchOverwrite() {
        val files = FakeFiles(failNames = setOf("android-1x1-s1-5.y"))
        val first = recorder(files)
        repeat(6) { first.record(byteArrayOf(it.toByte()), 1, 1, 1, "x") }
        assertEquals(5, files.manifest.size)
        recorder(files).record(byteArrayOf(9), 1, 1, 1, "y")
        assertContentEquals(byteArrayOf(5), files.written["android-1x1-s1-6.y"])
        assertContentEquals(byteArrayOf(9), files.written["android-1x1-s1-7.y"])
    }

    @Test
    fun unparseableNamesAreIgnoredWhenSeeding() {
        val extraNames = listOf("manifest.jsonl", "notes.y", "android-1x1-s1-x.y", "android-3x3-s3-4.y")
        val files = FakeFiles(extraNames = extraNames)
        recorder(files).record(ByteArray(1), 1, 1, 1, "x")
        assertEquals(setOf("android-1x1-s1-5.y"), files.written.keys)
    }
}
