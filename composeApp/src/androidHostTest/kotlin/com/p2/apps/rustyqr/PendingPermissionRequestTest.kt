package com.p2.apps.rustyqr

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingPermissionRequestTest {
    @AfterTest
    fun clear() {
        PendingPermissionRequest.cancel()
    }

    @Test
    fun theFirstRequestMustShowTheDialog() {
        assertTrue(PendingPermissionRequest.add { })
    }

    @Test
    fun aRequestWhileOneIsPendingSharesItsResult() {
        val results = mutableListOf<String>()
        assertTrue(PendingPermissionRequest.add { results += "first=$it" })
        assertFalse(PendingPermissionRequest.add { results += "second=$it" })

        PendingPermissionRequest.complete(granted = true)

        assertEquals(listOf("first=true", "second=true"), results)
    }

    @Test
    fun aResultReachesTheCallbackEvenIfItArrivesThroughAnotherActivity() {
        // The Activity that asked is gone; only this process-scoped queue still knows who is waiting.
        var granted: Boolean? = null
        PendingPermissionRequest.add { granted = it }

        PendingPermissionRequest.complete(granted = false)

        assertEquals(false, granted)
    }

    @Test
    fun completingClearsTheQueueSoTheNextRequestShowsTheDialog() {
        var calls = 0
        PendingPermissionRequest.add { calls++ }
        PendingPermissionRequest.complete(granted = true)
        PendingPermissionRequest.complete(granted = true)

        assertEquals(1, calls)
        assertTrue(PendingPermissionRequest.add { })
    }

    @Test
    fun cancelDropsTheWaitingCallbacks() {
        var calls = 0
        PendingPermissionRequest.add { calls++ }

        PendingPermissionRequest.cancel()
        PendingPermissionRequest.complete(granted = true)

        assertEquals(0, calls)
    }
}
