package com.p2.apps.rustyqr

import android.content.Context

/**
 * Process-scoped application [Context] for bridges that don't need an Activity.
 *
 * Initialized from [RustyQrApplication.onCreate], so it is non-null for the entire
 * lifetime of any component that can run (including during Activity rotation).
 */
object AppContextHolder {
    lateinit var context: Context
}
