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

    /**
     * Call when the host Activity is destroyed. The callbacks belong to ViewModels, which outlive a configuration
     * change (a rotation) and are cleared otherwise, so the queue is kept for the first and dropped for the second:
     * after that no result can reach anyone who still wants it.
     */
    fun hostDestroyed(changingConfigurations: Boolean) {
        if (!changingConfigurations) cancel()
    }

    /** Drops every queued callback. */
    fun cancel() {
        callbacks.clear()
    }
}
