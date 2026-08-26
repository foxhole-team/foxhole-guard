package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TorBridgeTransport
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.packages
import com.foxhole.core.model.withLane
import com.foxhole.guard.BuildConfig

suspend fun SettingsRepository.updatePrivacyRouteMode(value: PrivacyRouteMode) =
    update { current -> updatePrivacyRouteModeIn(current, value) }

internal fun updatePrivacyRouteModeIn(
    current: Settings,
    value: PrivacyRouteMode,
): Settings =
    current.copy(
        connection =
        current.connection.copy(
            safeModeEnabled = current.connection.safeModeEnabled && value == PrivacyRouteMode.OFF,
        ),
        traffic =
        if (value == PrivacyRouteMode.TOR_OVER_VPN) {
            current.traffic.copy(mode = TrafficMode.TUNNEL)
        } else {
            current.traffic
        },
        privacyRoute = current.privacyRoute.copy(mode = value),
    )

suspend fun SettingsRepository.updatePrivacyRoutePermitted(value: Boolean) =
    update { current -> updatePrivacyRoutePermissionIn(current, value) }

internal fun updatePrivacyRoutePermissionIn(
    current: Settings,
    permitted: Boolean,
    enabledAtMs: Long = System.currentTimeMillis(),
): Settings =
    current.copy(
        connection =
        current.connection.copy(
            safeModeEnabled = current.connection.safeModeEnabled && !permitted,
        ),
        privacyRoute =
        current.privacyRoute.copy(
            permitted = permitted,
            mode = if (permitted) current.privacyRoute.mode else PrivacyRouteMode.OFF,
        ),
        ui =
        current.ui.copy(
            torEnabledAtMs =
            when {
                !permitted -> 0L
                current.ui.torEnabledAtMs != 0L -> current.ui.torEnabledAtMs
                else -> enabledAtMs
            },
        ),
    )

suspend fun SettingsRepository.updatePrivacyRouteScope(value: PrivacyRouteScope) =
    update { current ->
        current.copy(
            connection =
            current.connection.copy(
                safeModeEnabled =
                current.connection.safeModeEnabled &&
                    value == PrivacyRouteScope.SELECTED_APPS &&
                    !current.privacyRoute.enabled &&
                    current.expert.packages(AppTunnelLane.TOR).isEmpty(),
            ),
            privacyRoute = current.privacyRoute.copy(scope = value),
        )
    }

suspend fun SettingsRepository.updatePrivacyRouteBypassVpnTunnel(value: Boolean) =
    update { current -> updatePrivacyRouteBypassVpnTunnelIn(current, value) }

internal fun updatePrivacyRouteBypassVpnTunnelIn(
    current: Settings,
    value: Boolean,
): Settings =
    current.copy(
        connection =
        current.connection.copy(
            safeModeEnabled = current.connection.safeModeEnabled && !value,
        ),
        traffic =
        if (value && current.privacyRoute.enabled) {
            current.traffic.copy(mode = TrafficMode.TUNNEL)
        } else {
            current.traffic
        },
        privacyRoute =
        current.privacyRoute.copy(
            bypassVpnTunnel = value,
        ),
    )

suspend fun SettingsRepository.updatePrivacyRouteModeAndBypassVpnTunnel(
    mode: PrivacyRouteMode,
    bypassVpnTunnel: Boolean,
) =
    update { current -> updatePrivacyRouteModeAndBypassVpnTunnelIn(current, mode, bypassVpnTunnel) }

internal fun updatePrivacyRouteModeAndBypassVpnTunnelIn(
    current: Settings,
    mode: PrivacyRouteMode,
    bypassVpnTunnel: Boolean,
): Settings =
    updatePrivacyRouteBypassVpnTunnelIn(
        current = updatePrivacyRouteModeIn(current, mode),
        value = bypassVpnTunnel,
    )

suspend fun SettingsRepository.updatePrivacyRouteAutoRotateExit(value: Boolean) =
    update { current -> updatePrivacyRouteAutoRotateExitIn(current, value) }

internal fun updatePrivacyRouteAutoRotateExitIn(
    current: Settings,
    value: Boolean,
): Settings = current.copy(privacyRoute = current.privacyRoute.copy(autoRotateExit = value))

suspend fun SettingsRepository.updatePrivacyRouteBlockAppsWhenTorUnavailable(value: Boolean) =
    update { current -> updatePrivacyRouteBlockAppsWhenTorUnavailableIn(current, value) }

internal fun updatePrivacyRouteBlockAppsWhenTorUnavailableIn(
    current: Settings,
    value: Boolean,
): Settings = current.copy(privacyRoute = current.privacyRoute.copy(blockAppsWhenTorUnavailable = value))

suspend fun SettingsRepository.updatePrivacyRouteAutoRotateInterval(value: Int) =
    update { current -> updatePrivacyRouteAutoRotateIntervalIn(current, value) }

internal fun updatePrivacyRouteAutoRotateIntervalIn(
    current: Settings,
    value: Int,
): Settings = current.copy(privacyRoute = current.privacyRoute.copy(autoRotateIntervalMinutes = value))

suspend fun SettingsRepository.updatePrivacyRouteSelectedPackages(value: List<String>) =
    update { current ->
        val selectedPackages = value.filterNot { it == BuildConfig.APPLICATION_ID }

        val demoted =
            current.expert.packages(AppTunnelLane.TOR).filterNot { it in selectedPackages }
        current.copy(
            connection =
            current.connection.copy(
                safeModeEnabled =
                current.connection.safeModeEnabled &&
                    selectedPackages.isEmpty() &&
                    !current.privacyRoute.enabled,
            ),
            expert =
            current.expert
                .withLane(AppTunnelLane.VPN, demoted)
                .withLane(AppTunnelLane.TOR, selectedPackages),
        )
    }

suspend fun SettingsRepository.rotatePrivacyRouteIdentity() =
    update { current ->
        current.copy(
            privacyRoute =
            current.privacyRoute.copy(
                identityVersion = System.currentTimeMillis(),
            ),
        )
    }

suspend fun SettingsRepository.updatePrivacyRouteBridgesEnabled(value: Boolean) =
    update { current -> updatePrivacyRouteBridgesEnabledIn(current, value) }

internal fun updatePrivacyRouteBridgesEnabledIn(
    current: Settings,
    value: Boolean,
): Settings = current.copy(privacyRoute = current.privacyRoute.copy(bridgesEnabled = value))

suspend fun SettingsRepository.updatePrivacyRouteBridgeTransport(value: TorBridgeTransport) =
    update { current -> updatePrivacyRouteBridgeTransportIn(current, value) }

internal fun updatePrivacyRouteBridgeTransportIn(
    current: Settings,
    value: TorBridgeTransport,
): Settings = current.copy(privacyRoute = current.privacyRoute.copy(bridgeTransport = value))

suspend fun SettingsRepository.updatePrivacyRouteBridgesAutoUpdate(value: Boolean) =
    update { current -> updatePrivacyRouteBridgesAutoUpdateIn(current, value) }

internal fun updatePrivacyRouteBridgesAutoUpdateIn(
    current: Settings,
    value: Boolean,
): Settings = current.copy(privacyRoute = current.privacyRoute.copy(bridgesAutoUpdate = value))

suspend fun SettingsRepository.updatePrivacyRouteBridgesUseFoxholeSource(value: Boolean) =
    update { current -> updatePrivacyRouteBridgesUseFoxholeSourceIn(current, value) }

internal fun updatePrivacyRouteBridgesUseFoxholeSourceIn(
    current: Settings,
    value: Boolean,
): Settings = current.copy(privacyRoute = current.privacyRoute.copy(bridgesUseFoxholeSource = value))

suspend fun SettingsRepository.markTorBridgesUpdated(timestamp: Long = System.currentTimeMillis()) =
    update { current ->
        current.copy(
            privacyRoute =
            current.privacyRoute.copy(
                bridgesUpdatedAt = timestamp,
                bridgesCheckedAt = timestamp,
                bridgesLastUpdateSuccess = true,
            ),
        )
    }

suspend fun SettingsRepository.markTorBridgesChecked(
    success: Boolean,
    timestamp: Long = System.currentTimeMillis(),
) = update { current ->
    current.copy(
        privacyRoute =
        current.privacyRoute.copy(
            bridgesCheckedAt = timestamp,
            bridgesLastUpdateSuccess = success,
        ),
    )
}
