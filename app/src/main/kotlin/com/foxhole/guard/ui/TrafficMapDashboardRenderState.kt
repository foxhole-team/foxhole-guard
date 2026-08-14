package com.foxhole.guard.ui

internal enum class TrafficMapDashboardRenderState {
    LOADING,
    ERROR,
    DISABLED,
    EMPTY,
    RENDERED,
}

/** The OWN runtime's connection phase, the only thing that moves the fox terminal. */
internal enum class TrafficMapFoxPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}
