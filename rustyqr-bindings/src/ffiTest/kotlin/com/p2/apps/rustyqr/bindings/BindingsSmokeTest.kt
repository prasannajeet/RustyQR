package com.p2.apps.rustyqr.bindings

import com.p2.apps.rustyqr.rust.FfiQrException
import com.p2.apps.rustyqr.rust.contentFits
import com.p2.apps.rustyqr.rust.decodeQr
import com.p2.apps.rustyqr.rust.generatePng
import com.p2.apps.rustyqr.rust.getLibraryVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val QR_SIZE_PX = 256u
private const val CONTENT = "hello"
private const val DIGIT_RUN_UNIT = "x12345"
private const val NUMERIC_CAPACITY = 5596
private const val ALPHANUMERIC_CAPACITY = 3391
private const val BYTE_CAPACITY = 2331
private const val MIXED_UNIT = "id=0123456789012345678901234567890123456789;"
private const val EMOJI = "\uD83D\uDE00"
private val EMOJI_COUNTS = listOf(500, 582, 583, 1000, 1500)
private val MIXED_CONTENT_LENGTHS = listOf(BYTE_CAPACITY, 3000, 3500, 4000, 4500)

class BindingsSmokeTest {
    @Test
    fun libraryVersionIsNotBlank() {
        assertTrue(getLibraryVersion().isNotBlank())
    }

    @Test
    fun generatedPngDecodesToOriginalContent() {
        val png = generatePng(CONTENT, QR_SIZE_PX)

        assertEquals(CONTENT, decodeQr(png).content)
    }

    @Test
    fun emptyContentFailsWithInvalidInput() {
        assertFailsWith<FfiQrException.InvalidInput> {
            generatePng("", QR_SIZE_PX)
        }
    }

    @Test
    fun numericContentFitsExactlyAtCapacity() = assertBoundary("7", NUMERIC_CAPACITY)

    @Test
    fun alphanumericContentFitsExactlyAtCapacity() = assertBoundary("A", ALPHANUMERIC_CAPACITY)

    @Test
    fun byteContentFitsExactlyAtCapacity() = assertBoundary("a", BYTE_CAPACITY)

    @Test
    fun multibyteContentIsMeasuredInBytes() {
        val fitting = "a" + "\u00e9".repeat((BYTE_CAPACITY - 1) / 2)
        assertEquals(BYTE_CAPACITY, fitting.encodeToByteArray().size)
        assertTrue(contentFits(fitting))
        assertTrue(generatePng(fitting, QR_SIZE_PX).isNotEmpty())

        val overflowing = "\u00e9".repeat((BYTE_CAPACITY + 1) / 2)
        assertEquals(BYTE_CAPACITY + 1, overflowing.encodeToByteArray().size)
        assertFalse(contentFits(overflowing))
        assertFailsWith<FfiQrException.ContentTooLong> { generatePng(overflowing, QR_SIZE_PX) }
    }

    @Test
    fun digitRunBoundaryIsTheByteFallback() {
        assertTrue(contentFits(digitRunContent(BYTE_CAPACITY)))
        assertTrue(generatePng(digitRunContent(BYTE_CAPACITY), QR_SIZE_PX).isNotEmpty())
        assertFalse(contentFits(digitRunContent(BYTE_CAPACITY + 1)))
        assertFailsWith<FfiQrException.ContentTooLong> {
            generatePng(digitRunContent(BYTE_CAPACITY + 1), QR_SIZE_PX)
        }
    }

    @Test
    fun contentFitsAgreesWithGenerationForMixedContent() {
        for (length in MIXED_CONTENT_LENGTHS) {
            assertAgreesWithGeneration(MIXED_UNIT.repeat(length).take(length), "length $length")
        }
    }

    @Test
    fun contentFitsAgreesWithGenerationForSurrogatePairs() {
        for (count in EMOJI_COUNTS) {
            assertAgreesWithGeneration(EMOJI.repeat(count), "emoji count $count")
        }
    }

    @Test
    fun emptyContentCannotBeChecked() {
        assertFailsWith<FfiQrException.InvalidInput> { contentFits("") }
    }

    private fun assertAgreesWithGeneration(
        content: String,
        message: String,
    ) {
        if (contentFits(content)) {
            assertTrue(generatePng(content, QR_SIZE_PX).isNotEmpty(), message)
        } else {
            assertFailsWith<FfiQrException.ContentTooLong>(message) { generatePng(content, QR_SIZE_PX) }
        }
    }

    private fun assertBoundary(
        unit: String,
        capacity: Int,
    ) {
        assertTrue(contentFits(unit.repeat(capacity)))
        assertTrue(generatePng(unit.repeat(capacity), QR_SIZE_PX).isNotEmpty())
        assertFalse(contentFits(unit.repeat(capacity + 1)))
        assertFailsWith<FfiQrException.ContentTooLong> { generatePng(unit.repeat(capacity + 1), QR_SIZE_PX) }
    }

    private fun digitRunContent(length: Int): String = DIGIT_RUN_UNIT.repeat(length).take(length)
}
