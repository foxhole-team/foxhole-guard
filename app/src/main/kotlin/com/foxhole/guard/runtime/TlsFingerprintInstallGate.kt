package com.foxhole.guard.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal class TlsFingerprintInstallGate(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    install: () -> Unit,
) {
    private val ready = scope.async(dispatcher, start = CoroutineStart.LAZY) { install() }

    fun start() {
        ready.start()
    }

    suspend fun awaitReady() {
        ready.await()
    }
}
