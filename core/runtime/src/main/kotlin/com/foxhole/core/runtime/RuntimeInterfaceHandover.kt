package com.foxhole.core.runtime

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Establishes the replacement before closing every quiesced master TUN, even on cancellation. */
suspend fun <T> RuntimeInstanceStore.establishNextInterfaceThenStopRetired(
    stopRetired: suspend (FoxholeRuntime) -> Unit,
    establishNextInterface: suspend () -> T,
): T =
    try {
        establishNextInterface()
    } finally {
        withContext(NonCancellable) {
            stopEveryRetiredRuntime(stopRetired)
        }
    }

suspend fun RuntimeInstanceStore.stopEveryRetiredRuntime(stopRetired: suspend (FoxholeRuntime) -> Unit) {
    while (true) {
        val retired = takeRetired() ?: return
        stopRetired(retired)
    }
}
