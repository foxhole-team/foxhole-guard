package com.foxhole.core.model

/**
 * Connection states considered "active" (a runtime session is connecting or up). Pure data shared by
 * the runtime engine and the app, so it lives in `:core:model` rather than being duplicated in the
 * host controller and the view model.
 */
val ACTIVE_CONNECTION_STATES: Set<ConnectionState> =
    setOf(
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
    )
