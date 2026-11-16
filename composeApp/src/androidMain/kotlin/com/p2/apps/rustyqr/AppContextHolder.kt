package com.p2.apps.rustyqr

import android.content.Context

/**
 * Process-scoped application [Context] for bridges that don't need an Activity.
 *
 * Set by [AppContextInitializer], which androidx.startup runs before any component of the process, so it is
 * non-null for the entire lifetime of anything that can run (including during Activity rotation).
 */
internal object AppContextHolder {
    lateinit var context: Context
}
