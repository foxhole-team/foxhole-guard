package com.foxhole.guard.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The bus every user-facing banner rides. In the CLI front end these events are not transient
 * Material snackbars: [com.foxhole.guard.ui.cli.CliApp] prints each one into the terminal, which is
 * the session journal — the place a user looks to answer "why did it reconnect while the phone was
 * in my pocket".
 *
 * That is why this is a [Channel] and not a `MutableSharedFlow`. A shared flow with `replay = 0`
 * drops every emission made while nobody collects, and the only collector lives in a
 * `LaunchedEffect` inside the composition: between `onDestroy` and the next `setContent` — the
 * whole time the activity is gone and the VPN service keeps reporting errors and reconnects — the
 * journal lines were emitted into nothing. A channel buffers them instead, and the next composition
 * plays back what it missed.
 *
 * SINGLE CONSUMER, deliberately. `receiveAsFlow` hands each element to exactly one collector, so
 * two concurrent collectors would split the journal between them instead of both seeing it. That is
 * acceptable — and enforced by `FoxholeBannerEventsTest` — because the app has exactly one:
 * `CliBannerBridge`. Anything that needs a second reader must not add one here; it has to fan out
 * from the bridge (or this type has to grow an explicit broadcast, which a channel cannot give).
 *
 * Overflow drops the OLDEST line rather than suspending the producer. Producers are the runtime and
 * settings coroutines of the view model; making them block on a full buffer while no UI exists
 * would wedge them for as long as the app stays closed, which is a worse failure than losing the
 * head of a 64-deep backlog. `send`/[emit] therefore never suspends and [tryEmit] never fails,
 * matching the never-blocking behaviour the shared flow used to have.
 */
internal class FoxholeBannerEvents(capacity: Int = DEFAULT_CAPACITY) {

    private val channel =
        Channel<FoxholeBannerEvent>(capacity = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The single-consumer stream. Collecting it twice at once splits the events, see the class KDoc. */
    val stream: Flow<FoxholeBannerEvent> = channel.receiveAsFlow()

    /** Non-suspending emit for call sites outside a coroutine. Always succeeds, see the class KDoc. */
    fun tryEmit(event: FoxholeBannerEvent): Boolean = channel.trySend(event).isSuccess

    /** Suspending emit, kept for call sites already inside a coroutine. Never actually suspends. */
    suspend fun emit(event: FoxholeBannerEvent) {
        channel.send(event)
    }

    companion object {
        /**
         * Deep enough for a background stretch of reconnect narration (the terminal itself keeps
         * 120 lines), small enough that a forgotten backlog cannot grow without bound.
         */
        const val DEFAULT_CAPACITY = 64
    }
}
