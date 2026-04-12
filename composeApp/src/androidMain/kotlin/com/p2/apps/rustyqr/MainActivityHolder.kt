package com.p2.apps.rustyqr

import java.lang.ref.WeakReference

/**
 * Holds a weak reference to [MainActivity] so that platform bridges can access it.
 *
 * WeakReference ensures we don't leak the Activity on rotation or finish.
 */
object MainActivityHolder {
    private var activityRef: WeakReference<MainActivity>? = null

    var activity: MainActivity?
        get() = activityRef?.get()
        set(value) {
            activityRef = if (value != null) WeakReference(value) else null
        }
}
