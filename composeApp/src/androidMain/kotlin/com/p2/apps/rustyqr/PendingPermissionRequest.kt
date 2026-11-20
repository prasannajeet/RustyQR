package com.p2.apps.rustyqr

/**
 * The callbacks waiting for the system permission dialog, kept for the process rather than for one Activity.
 *
 * Rotating while the dialog is open destroys the Activity that asked, and the recreated one registers a new result
 * launcher. The result still reaches the callbacks queued here, because they live outside both Activities. Main
 * thread only.
 */
internal object PendingPermissionRequest {
    private val callbacks = mutableListOf<(Boolean) -> Unit>()

    /**
     * Queues [onResult] for the pending dialog.
     *
     * @return true when no request was pending, so the caller must show the dialog; false when one is already open
     *   and [onResult] will receive its result.
     */
    fun add(onResult: (Boolean) -> Unit): Boolean {
        val isFirst = callbacks.isEmpty()
        callbacks += onResult
        return isFirst
    }

    /** Delivers [granted] to every queued callback and clears the queue. */
    fun complete(granted: Boolean) {
        val waiting = callbacks.toList()
        callbacks.clear()
        waiting.forEach { it(granted) }
    }

    /** Drops every queued callback, for when the dialog's result can no longer arrive. */
    fun cancel() {
        callbacks.clear()
    }
}
