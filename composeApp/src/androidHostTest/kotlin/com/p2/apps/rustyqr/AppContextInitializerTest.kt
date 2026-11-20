package com.p2.apps.rustyqr

import android.content.Context
import android.content.ContextWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AppContextInitializerTest {
    /** A context whose [getApplicationContext] is another, distinguishable context, like an Activity's. */
    private class FakeContext(
        private val application: Context?,
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = application ?: this
    }

    @Test
    fun createStoresTheApplicationContext() {
        val application = FakeContext(application = null)
        AppContextInitializer().create(FakeContext(application))
        assertSame(application, AppContextHolder.context)
    }

    @Test
    fun hasNoInitializerDependencies() {
        assertEquals(emptyList(), AppContextInitializer().dependencies())
    }
}
