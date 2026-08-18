package com.foxhole.guard.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A Channel, not a SharedFlow: replay=0 dropped every event emitted while the composition was gone, so the buffer replays them to the next collector.
 * Single consumer by contract (receiveAsFlow splits elements); overflow drops the oldest line so producers never suspend.
 */
internal class FoxholeBannerEvents(capacity: Int = DEFAULT_CAPACITY) {

    private val channel =
        Channel<FoxholeBannerEvent>(capacity = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val stream: Flow<FoxholeBannerEvent> = channel.receiveAsFlow()

    fun tryEmit(event: FoxholeBannerEvent): Boolean = channel.trySend(event).isSuccess

    suspend fun emit(event: FoxholeBannerEvent) {
        channel.send(event)
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
