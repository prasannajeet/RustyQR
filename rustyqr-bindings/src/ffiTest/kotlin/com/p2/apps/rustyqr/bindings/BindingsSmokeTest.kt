package com.p2.apps.rustyqr.bindings

import com.p2.apps.rustyqr.rust.FfiQrException
import com.p2.apps.rustyqr.rust.contentFits
import com.p2.apps.rustyqr.rust.decodeQr
import com.p2.apps.rustyqr.rust.generatePng
import com.p2.apps.rustyqr.rust.getLibraryVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal const val QR_SIZE_PX = 256u
private const val CONTENT = "hello"

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
    fun emptyContentCannotBeChecked() {
        assertFailsWith<FfiQrException.InvalidInput> { contentFits("") }
    }
}
