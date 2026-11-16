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
        assertEquals(19L, percentile(input, 95))
        assertEquals(10L, percentile(input, 50))
        assertEquals(20L, input.first())
    }

    @Test
    fun percentileClampsPercentAndHandlesEmpty() {
        assertEquals(1L, percentile(listOf(3L, 1L, 2L), 0))
        assertEquals(3L, percentile(listOf(3L, 1L, 2L), 500))
        assertNull(percentile(emptyList(), 50))
    }

    @Test
    fun logsMedianAndP95PerStageEveryHundredFramesThenResets() {
        var now = 0L
        val lines = mutableListOf<String>()
        val timings = FrameTimings(logFrameTimings = true, captureFrames = false, nowNanos = { now }, log = lines::add)
        val hit: Either<String, Int> = 1.right()
        val miss: Either<String, Int> = "none".left()

        repeat(100) { index ->
            timings.startFrame()
            now += 2_000
            timings.endCopy()
            val isHit = index % 2 == 0
            now += if (isHit) 10_000 else 20_000
            timings.endRust(if (isHit) hit else miss)
            timings.endFrame()
        }

        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue("copy n=100 median=2us p95=2us" in line, line)
        assertTrue("rust_hit n=50 median=10us p95=10us" in line, line)
        assertTrue("rust_miss n=50 median=20us p95=20us" in line, line)
        assertTrue("total n=100 median=12us p95=22us" in line, line)

        timings.startFrame()
        timings.endCopy()
        timings.endRust(hit)
        timings.endFrame()
        assertEquals(1, lines.size)
    }

    @Test
    fun disabledTimingsNeverReadTheClockOrLog() {
        val lines = mutableListOf<String>()
        val timings =
            FrameTimings(
                logFrameTimings = false,
                captureFrames = false,
                nowNanos = { error("clock must not be read") },
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

    @Test
    fun capturingFramesDisablesTimingsAndWarnsAtMostOnce() {
        val lines = mutableListOf<String>()
        val timings =
            FrameTimings(
                logFrameTimings = true,
                captureFrames = true,
                nowNanos = { error("clock must not be read") },
                log = lines::add,
            )
        repeat(200) {
            timings.startFrame()
            timings.endCopy()
            timings.endRust(1.right())
            timings.endFrame()
        }
        assertFalse(timings.isEnabled)
        assertTrue(lines.size <= 1)
        assertTrue(lines.all { "CAPTURE_FRAMES" in it })
    }
}
