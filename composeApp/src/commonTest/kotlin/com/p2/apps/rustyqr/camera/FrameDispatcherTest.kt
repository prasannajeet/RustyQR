package com.p2.apps.rustyqr.camera

import com.p2.apps.rustyqr.model.CameraFailure
import com.p2.apps.rustyqr.model.ScanResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameDispatcherTest {
    private val mainQueue = ArrayDeque<() -> Unit>()
    private var scanning = true
    private var decodedSink: (ScanResult) -> Unit = {}
    private var readySink: () -> Unit = {}
    private var errorSink: (CameraFailure) -> Unit = {}

    private val dispatcher =
        FrameDispatcher(
            isScanning = { scanning },
            onQrDecoded = { decodedSink(it) },
            onCameraReady = { readySink() },
            onCameraError = { errorSink(it) },
            postToMain = { mainQueue.addLast(it) },
        )

    private fun runMain() {
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst()()
    }

    @Test
    fun latestDecodeCallbackWinsEvenWhenReplacedWhileFrameIsInFlight() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        decodedSink = { first += it.content }
        dispatcher.deliver(ScanResult("a"))
        decodedSink = { second += it.content }
        runMain()
        assertEquals(emptyList(), first)
        assertEquals(listOf("a"), second)
    }

    @Test
    fun latestReadyCallbackWinsAndFiresOnce() {
        var firstCalls = 0
        var secondCalls = 0
        readySink = { firstCalls++ }
        dispatcher.onFrame()
        readySink = { secondCalls++ }
        runMain()
        dispatcher.onFrame()
        runMain()
        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun scanningFlagIsReadPerFrame() {
        assertTrue(dispatcher.onFrame())
        scanning = false
        assertFalse(dispatcher.onFrame())
        scanning = true
        assertTrue(dispatcher.onFrame())
    }

    @Test
    fun readyFiresEvenWhileNotScanning() {
        var ready = 0
        readySink = { ready++ }
        scanning = false
        assertFalse(dispatcher.onFrame())
        runMain()
        assertEquals(1, ready)
    }

    @Test
    fun closeDropsPostedAndFutureCallbacks() {
        var calls = 0
        decodedSink = { calls++ }
        readySink = { calls++ }
        dispatcher.onFrame()
        dispatcher.deliver(ScanResult("a"))
        dispatcher.close()
        dispatcher.deliver(ScanResult("b"))
        runMain()
        assertEquals(0, calls)
    }

    @Test
    fun onFrameAfterCloseReturnsFalseAndPostsNothing() {
        dispatcher.close()
        assertFalse(dispatcher.onFrame())
        assertTrue(mainQueue.isEmpty())
    }

    @Test
    fun failureIsDeliveredOnceToTheLatestCallback() {
        val first = mutableListOf<CameraFailure>()
        val second = mutableListOf<CameraFailure>()
        errorSink = { first += it }
        dispatcher.fail(CameraFailure.NoCamera)
        errorSink = { second += it }
        dispatcher.fail(CameraFailure.CouldNotStart)
        runMain()
        assertEquals(emptyList(), first)
        assertEquals(listOf(CameraFailure.NoCamera), second)
    }

    @Test
    fun failureAfterCloseOrPostedBeforeCloseIsDropped() {
        var calls = 0
        errorSink = { calls++ }
        dispatcher.fail(CameraFailure.NoCamera)
        dispatcher.close()
        runMain()
        dispatcher.fail(CameraFailure.CouldNotStart)
        runMain()
        assertEquals(0, calls)
    }

    @Test
    fun afterFailureFramesAreNotDecodedAndNoReadyIsPosted() {
        var ready = 0
        readySink = { ready++ }
        dispatcher.fail(CameraFailure.CouldNotStart)

        assertFalse(dispatcher.onFrame())
        runMain()

        assertEquals(0, ready)
    }

    @Test
    fun readyPostedBeforeFailureIsDropped() {
        var ready = 0
        readySink = { ready++ }
        dispatcher.onFrame()
        dispatcher.fail(CameraFailure.CouldNotStart)
        runMain()
        assertEquals(0, ready)
    }

    @Test
    fun readyThenFailureBothFireInOrder() {
        val events = mutableListOf<String>()
        readySink = { events += "ready" }
        errorSink = { events += "error" }
        dispatcher.onFrame()
        runMain()
        dispatcher.fail(CameraFailure.CouldNotStart)
        runMain()
        assertEquals(listOf("ready", "error"), events)
    }

    @Test
    fun deliverAfterFailureIsDropped() {
        var decoded = 0
        decodedSink = { decoded++ }
        dispatcher.deliver(ScanResult("in flight"))
        dispatcher.fail(CameraFailure.CouldNotStart)
        dispatcher.deliver(ScanResult("late"))
        runMain()
        assertEquals(0, decoded)
    }
}
