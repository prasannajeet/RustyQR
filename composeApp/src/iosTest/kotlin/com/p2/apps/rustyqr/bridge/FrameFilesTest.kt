package com.p2.apps.rustyqr.bridge

import arrow.core.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FrameFilesTest {
    @Test
    fun readLinesOfMissingFileIsEmpty() {
        val files = assertNotNull(frameFiles().getOrNull())

        assertEquals(Either.Right(emptyList()), files.readLines("missing-${this.hashCode()}.jsonl"))
    }

    @Test
    fun readLinesReturnsAppendedLinesWithoutTrailingEmptyLine() {
        val files = assertNotNull(frameFiles().getOrNull())
        val name = "read-lines-test.jsonl"
        files.writeBytes(name, ByteArray(0))
        files.appendLine(name, "first")
        files.appendLine(name, "second")

        assertEquals(Either.Right(listOf("first", "second")), files.readLines(name))
    }
}
