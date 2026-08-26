package com.foxhole.core.model

val ACTIVE_CONNECTION_STATES: Set<ConnectionState> =
    setOf(
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
    )
