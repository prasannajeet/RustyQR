package com.p2.apps.rustyqr.camera

import arrow.core.Either
import com.p2.apps.rustyqr.model.QrError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class LumaRowsTest {
    private fun right(result: Either<QrError, ByteArray>): ByteArray = assertIs<Either.Right<ByteArray>>(result).value

    private fun assertInvalid(result: Either<QrError, ByteArray>) {
        assertIs<QrError.InvalidInput>(assertIs<Either.Left<QrError>>(result).value)
    }

    @Test
    fun unpaddedInputIsReturnedWithoutCopy() {
        val src = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertSame(src, right(packLumaRows(src, 3, 2, 3)))
    }

    @Test
    fun paddedInputDropsPadding() {
        val src = byteArrayOf(1, 2, 3, 9, 9, 4, 5, 6, 9, 9)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), right(packLumaRows(src, 3, 2, 5)))
    }

    @Test
    fun unpaddedLastRowIsAccepted() {
        val src = byteArrayOf(1, 2, 3, 9, 9, 4, 5, 6)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), right(packLumaRows(src, 3, 2, 5)))
    }

    @Test
    fun oversizedTightSourceIsTrimmed() {
        val src = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), right(packLumaRows(src, 3, 2, 3)))
    }

    @Test
    fun nonPositiveWidthOrHeightIsInvalid() {
        val src = ByteArray(16)
        assertInvalid(packLumaRows(src, 0, 2, 4))
        assertInvalid(packLumaRows(src, -1, 2, 4))
        assertInvalid(packLumaRows(src, 4, 0, 4))
        assertInvalid(packLumaRows(src, 4, -1, 4))
    }

    @Test
    fun strideSmallerThanWidthIsInvalid() {
        assertInvalid(packLumaRows(ByteArray(16), 4, 2, 3))
    }

    @Test
    fun overflowIsInvalid() {
        assertInvalid(packLumaRows(ByteArray(16), Int.MAX_VALUE, 2, Int.MAX_VALUE))
        assertInvalid(packLumaRows(ByteArray(16), 4, Int.MAX_VALUE, 4))
        assertInvalid(packLumaRows(ByteArray(16), 4, 3, Int.MAX_VALUE))
    }

    @Test
    fun shortSourceIsInvalid() {
        assertInvalid(packLumaRows(ByteArray(7), 3, 2, 5))
    }
}
