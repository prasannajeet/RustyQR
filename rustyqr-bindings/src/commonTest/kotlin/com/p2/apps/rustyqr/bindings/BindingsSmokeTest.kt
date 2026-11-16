package com.p2.apps.rustyqr.bindings

import com.p2.apps.rustyqr.rust.FfiQrException
import com.p2.apps.rustyqr.rust.decodeQr
import com.p2.apps.rustyqr.rust.generatePng
import com.p2.apps.rustyqr.rust.getLibraryVersion
import com.p2.apps.rustyqr.rust.maxContentBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val QR_SIZE_PX = 256u
private const val CONTENT = "hello"
private const val DIGIT_RUN_UNIT = "x12345"

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
    fun contentAtTheByteLimitEncodes() {
        assertTrue(generatePng("a".repeat(maxContentBytes().toInt()), QR_SIZE_PX).isNotEmpty())
    }

    @Test
    fun contentOneByteOverTheLimitFailsWithContentTooLong() {
        assertFailsWith<FfiQrException.ContentTooLong> {
            generatePng("a".repeat(maxContentBytes().toInt() + 1), QR_SIZE_PX)
        }
    }

    @Test
    fun digitRunContentAtTheByteLimitEncodes() {
        assertTrue(generatePng(digitRunContent(maxContentBytes().toInt()), QR_SIZE_PX).isNotEmpty())
    }

    @Test
    fun digitRunContentOneByteOverTheLimitFailsWithContentTooLong() {
        assertFailsWith<FfiQrException.ContentTooLong> {
            generatePng(digitRunContent(maxContentBytes().toInt() + 1), QR_SIZE_PX)
        }
    }

    private fun digitRunContent(length: Int): String = DIGIT_RUN_UNIT.repeat(length).take(length)
}
