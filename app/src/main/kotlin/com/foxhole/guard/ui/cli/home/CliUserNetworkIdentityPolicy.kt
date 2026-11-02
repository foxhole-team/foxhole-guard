package com.foxhole.guard.ui.cli.home

internal data class CliUserNetworkIdentityPresentation(val current: Boolean, val pending: Boolean)

internal fun cliUserNetworkIdentityPresentation(
    candidateFetchedAt: Long?,
    routeChangedAt: Long,
    networkInfoFloor: Long,
    routeTransition: Boolean,
    refreshing: Boolean,
    connected: Boolean,
    connectionMetricsLoading: Boolean,
    settingsHydrated: Boolean,
    profilesLoaded: Boolean,
): CliUserNetworkIdentityPresentation {
    val current = candidateFetchedAt != null && !routeTransition &&
        (routeChangedAt <= 0L || candidateFetchedAt >= routeChangedAt) && candidateFetchedAt > networkInfoFloor
    return CliUserNetworkIdentityPresentation(
        current = current,
        pending = routeTransition || refreshing || (connected && !current && connectionMetricsLoading) ||
            !settingsHydrated || !profilesLoaded,
    )
}
