package com.p2.apps.rustyqr.bindings

import com.p2.apps.rustyqr.rust.FfiQrException
import com.p2.apps.rustyqr.rust.contentFits
import com.p2.apps.rustyqr.rust.generatePng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DIGIT_RUN_UNIT = "x12345"
private const val NUMERIC_CAPACITY = 5596
private const val ALPHANUMERIC_CAPACITY = 3391
private const val BYTE_CAPACITY = 2331

// Non-ASCII content is UTF-8 behind a 12-bit ECI header, which leaves one byte less than the byte capacity.
private const val UTF8_CAPACITY = 2330

private const val MIXED_UNIT = "id=0123456789012345678901234567890123456789;"
private const val EMOJI = "😀"

// Each emoji is a surrogate pair in Kotlin and 4 bytes of UTF-8, so UTF8_CAPACITY / 4 = 582 fit and 583 do not.
private const val EMOJI_BYTES = 4
private const val EMOJI_FIT_COUNT = UTF8_CAPACITY / EMOJI_BYTES
private const val EMOJI_OVERFLOW_COUNT = EMOJI_FIT_COUNT + 1
private const val EMOJI_FAR_BELOW_COUNT = 500
private const val EMOJI_FAR_ABOVE_COUNT = 1000

// Mixed content (a byte-mode prefix, a long digit run, a byte-mode suffix) is split into segments, so more than
// BYTE_CAPACITY characters can fit. The verdicts are pinned: the Rust encoder decides them.
private const val MIXED_FITTING_LENGTH = 3500
private const val MIXED_OVERFLOWING_LENGTH = 4500

class ContentFitsBindingsTest {
    @Test
    fun numericContentFitsExactlyAtCapacity() = assertBoundary("7", NUMERIC_CAPACITY)

    @Test
    fun alphanumericContentFitsExactlyAtCapacity() = assertBoundary("A", ALPHANUMERIC_CAPACITY)

    @Test
    fun byteContentFitsExactlyAtCapacity() = assertBoundary("a", BYTE_CAPACITY)

    @Test
    fun multibyteContentIsMeasuredInBytes() {
        val fitting = "é".repeat(UTF8_CAPACITY / 2)
        assertEquals(UTF8_CAPACITY, fitting.encodeToByteArray().size)
        assertTrue(contentFits(fitting))
        assertTrue(generatePng(fitting, QR_SIZE_PX).isNotEmpty())

        val overflowing = fitting + "a"
        assertEquals(UTF8_CAPACITY + 1, overflowing.encodeToByteArray().size)
        assertFalse(contentFits(overflowing))
        assertFailsWith<FfiQrException.ContentTooLong> { generatePng(overflowing, QR_SIZE_PX) }
    }

    @Test
    fun digitRunBoundaryIsOneWholeByteSegment() {
        assertTrue(contentFits(digitRunContent(BYTE_CAPACITY)))
        assertTrue(generatePng(digitRunContent(BYTE_CAPACITY), QR_SIZE_PX).isNotEmpty())
        assertFalse(contentFits(digitRunContent(BYTE_CAPACITY + 1)))
        assertFailsWith<FfiQrException.ContentTooLong> {
            generatePng(digitRunContent(BYTE_CAPACITY + 1), QR_SIZE_PX)
        }
    }

    @Test
    fun mixedContentVerdictsArePinned() {
        assertVerdict(mixedContent(BYTE_CAPACITY), fits = true, message = "mixed length $BYTE_CAPACITY")
        assertVerdict(mixedContent(MIXED_FITTING_LENGTH), fits = true, message = "mixed length $MIXED_FITTING_LENGTH")
        assertVerdict(
            mixedContent(MIXED_OVERFLOWING_LENGTH),
            fits = false,
            message = "mixed length $MIXED_OVERFLOWING_LENGTH",
        )
    }

    @Test
    fun surrogatePairsAreMeasuredInUtf8Bytes() {
        val fitting = EMOJI.repeat(EMOJI_FIT_COUNT)
        assertEquals(EMOJI_FIT_COUNT * 2, fitting.length, "each emoji is a surrogate pair")
        assertTrue(fitting.encodeToByteArray().size <= UTF8_CAPACITY)
        assertTrue(EMOJI.repeat(EMOJI_OVERFLOW_COUNT).encodeToByteArray().size > UTF8_CAPACITY)

        assertVerdict(EMOJI.repeat(EMOJI_FAR_BELOW_COUNT), fits = true, message = "emoji count $EMOJI_FAR_BELOW_COUNT")
        assertVerdict(fitting, fits = true, message = "emoji count $EMOJI_FIT_COUNT")
        assertVerdict(EMOJI.repeat(EMOJI_OVERFLOW_COUNT), fits = false, message = "emoji count $EMOJI_OVERFLOW_COUNT")
        assertVerdict(EMOJI.repeat(EMOJI_FAR_ABOVE_COUNT), fits = false, message = "emoji count $EMOJI_FAR_ABOVE_COUNT")
    }

    private fun assertVerdict(
        content: String,
        fits: Boolean,
        message: String,
    ) {
        assertEquals(fits, contentFits(content), message)
        if (fits) {
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

    private fun mixedContent(length: Int): String = MIXED_UNIT.repeat(length).take(length)
}
