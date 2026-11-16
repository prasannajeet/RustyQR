package com.p2.apps.rustyqr

import java.lang.ref.WeakReference

/**
 * Holds a weak reference to the running [BridgeHostActivity] so that platform bridges can access it.
 *
 * WeakReference ensures we don't leak the Activity on rotation or finish.
 */
internal object BridgeHostActivityHolder {
    private var activityRef: WeakReference<BridgeHostActivity>? = null

    var activity: BridgeHostActivity?
        get() = activityRef?.get()
        set(value) {
            activityRef = if (value != null) WeakReference(value) else null
        }
}
