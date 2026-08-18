package com.foxhole.guard.ui

internal enum class TrafficMapDashboardRenderState {
    LOADING,
    ERROR,
    DISABLED,
    EMPTY,
    RENDERED,
}

internal enum class TrafficMapFoxPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}
