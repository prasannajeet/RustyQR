package com.p2.apps.rustyqr.debug

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameTimingsTest {
    @Test
    fun percentileOfSingleValueIsThatValue() {
        assertEquals(7L, percentile(listOf(7L), 50))
        assertEquals(7L, percentile(listOf(7L), 95))
    }

    @Test
    fun percentileOfOddCountPicksTheMiddle() {
        assertEquals(3L, percentile(listOf(1L, 2L, 3L, 4L, 5L), 50))
        assertEquals(5L, percentile(listOf(1L, 2L, 3L, 4L, 5L), 95))
    }

    @Test
    fun percentileOfEvenCountUsesNearestRank() {
        assertEquals(2L, percentile(listOf(1L, 2L, 3L, 4L), 50))
        assertEquals(4L, percentile(listOf(1L, 2L, 3L, 4L), 95))
    }

    @Test
    fun percentileSortsUnsortedInputWithoutModifyingIt() {
        val input = listOf(20L, 3L, 11L, 1L, 17L, 5L, 9L, 15L, 7L, 13L, 2L, 19L, 4L, 12L, 8L, 16L, 6L, 14L, 10L, 18L)
        val copy = input.toList()
        assertEquals(19L, percentile(input, 95))
        assertEquals(10L, percentile(input, 50))
        assertEquals(copy, input)
    }

    @Test
    fun percentileClampsPercentAndHandlesEmpty() {
        assertEquals(1L, percentile(listOf(3L, 1L, 2L), 0))
        assertEquals(3L, percentile(listOf(3L, 1L, 2L), 500))
        assertNull(percentile(emptyList(), 50))
    }

    private fun FrameTimings.runFrame(
        advance: (Long) -> Unit,
        copyNanos: Long,
        rustNanos: Long,
        result: Either<String, Int>,
    ) {
        startFrame()
        advance(copyNanos)
        endCopy()
        advance(rustNanos)
        endRust(result)
        endFrame()
    }

    @Test
    fun logsMedianAndP95PerStageEveryHundredFramesThenResets() {
        var now = 0L
        val lines = mutableListOf<String>()
        val timings = FrameTimings(logFrameTimings = true, clock = { now }, log = lines::add)
        val hit: Either<String, Int> = 1.right()
        val miss: Either<String, Int> = "none".left()
        val advance = { nanos: Long -> now += nanos }

        repeat(100) { index ->
            val isHit = index % 2 == 0
            timings.runFrame(advance, 2_000, if (isHit) 10_000 else 20_000, if (isHit) hit else miss)
        }

        assertEquals(1, lines.size)
        val first = lines.single()
        assertTrue("copy n=100 median=2us p95=2us" in first, first)
        assertTrue("rust_hit n=50 median=10us p95=10us" in first, first)
        assertTrue("rust_miss n=50 median=20us p95=20us" in first, first)
        assertTrue("total n=100 median=12us p95=22us" in first, first)

        repeat(100) { index ->
            val isHit = index % 2 == 0
            timings.runFrame(advance, 3_000, if (isHit) 40_000 else 70_000, if (isHit) hit else miss)
        }

        assertEquals(2, lines.size)
        val second = lines.last()
        assertTrue("copy n=100 median=3us p95=3us" in second, second)
        assertTrue("rust_hit n=50 median=40us p95=40us" in second, second)
        assertTrue("rust_miss n=50 median=70us p95=70us" in second, second)
        assertTrue("total n=100 median=43us p95=73us" in second, second)
    }

    @Test
    fun framesThatNeverReachRustAreNotCounted() {
        var now = 0L
        val lines = mutableListOf<String>()
        val timings = FrameTimings(logFrameTimings = true, clock = { now }, log = lines::add)
        repeat(300) {
            timings.startFrame()
            now += 1_000
            timings.endFrame()
        }
        assertTrue(lines.isEmpty())
    }

    @Test
    fun disabledTimingsNeverReadTheClockOrLog() {
        val lines = mutableListOf<String>()
        val timings =
            FrameTimings(
                logFrameTimings = false,
                clock = { error("clock must not be read") },
                log = lines::add,
            )
        assertFalse(timings.isEnabled)
        repeat(300) {
            timings.startFrame()
            timings.endCopy()
            timings.endRust(1.right())
            timings.endFrame()
        }
        assertTrue(lines.isEmpty())
    }
}
