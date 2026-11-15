package com.p2.apps.rustyqr.bridge

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs blocking platform I/O for the Android bridges on an injectable dispatcher. */
internal class BridgeIo(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun <T> run(block: suspend CoroutineScope.() -> T): T = withContext(dispatcher, block)
}

internal val bridgeIo = BridgeIo()
