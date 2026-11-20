@file:OptIn(ExperimentalForeignApi::class)

package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val LINES_FILE = "read-lines-test.jsonl"
private const val MISSING_FILE = "read-lines-missing.jsonl"
private const val TORN_FILE = "read-lines-torn.jsonl"
private const val INVALID_BYTE: Byte = 0xFF.toByte()

class FrameFilesTest {
    @AfterTest
    fun deleteTestFiles() {
        listOf(LINES_FILE, TORN_FILE).forEach {
            NSFileManager.defaultManager.removeItemAtPath("${framesDirectory()}/$it", null)
        }
    }

    @Test
    fun readLinesOfMissingFileIsEmpty() {
        val files = assertNotNull(frameFiles().getOrNull())

        assertEquals(Either.Right(emptyList()), files.readLines(MISSING_FILE))
    }

    @Test
    fun readLinesReturnsAppendedLinesWithoutTrailingEmptyLine() {
        val files = assertNotNull(frameFiles().getOrNull())
        files.writeBytes(LINES_FILE, ByteArray(0))
        files.appendLine(LINES_FILE, "first")
        files.appendLine(LINES_FILE, "second")

        assertEquals(Either.Right(listOf("first", "second")), files.readLines(LINES_FILE))
    }

    @Test
    fun readLinesReplacesMalformedUtf8InsteadOfFailing() {
        val files = assertNotNull(frameFiles().getOrNull())
        files.writeBytes(TORN_FILE, "ok\n".encodeToByteArray() + byteArrayOf(INVALID_BYTE) + "x\n".encodeToByteArray())

        assertEquals(Either.Right(listOf("ok", "\uFFFDx")), files.readLines(TORN_FILE))
    }
}
