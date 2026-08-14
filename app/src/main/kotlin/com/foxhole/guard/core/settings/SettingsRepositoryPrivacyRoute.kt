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

// Privacy route (Tor) settings. Extracted from SettingsRepository (class split by domain).

suspend fun SettingsRepository.updatePrivacyRouteMode(value: PrivacyRouteMode) =
    update { current ->
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
    }

// Permission-only flag: flipping it never touches mode/runtime here — the caller is
// responsible for stopping an engaged Tor before revoking (see onTorRoutePermittedChanged).
// Granting exits safe mode like the other Tor switches do: with safe mode on, normalized()
// resets the whole privacyRoute block, which used to turn this grant into a silent no-op.
suspend fun SettingsRepository.updatePrivacyRoutePermitted(value: Boolean) =
    update { current ->
        current.copy(
            connection =
            current.connection.copy(
                safeModeEnabled = current.connection.safeModeEnabled && !value,
            ),
            privacyRoute = current.privacyRoute.copy(permitted = value),
            // Enable-order stamp: the TOR/I2P settings entry names/orders itself by whichever
            // core came on first. A re-grant while already stamped keeps the original stamp.
            ui =
            current.ui.copy(
                torEnabledAtMs =
                when {
                    !value -> 0L
                    current.ui.torEnabledAtMs != 0L -> current.ui.torEnabledAtMs
                    else -> System.currentTimeMillis()
                },
            ),
        )
    }

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
    update { current ->
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
    }

// Tor tuning below carries NO safe-mode clause on purpose: safe mode governs only the four fields
// that engage the Tor lane (mode / permitted / scope / bypass), and Settings.normalized() disarms
// only those. The transforms are extracted as pure `...In` functions (same idiom as
// updateTunStackIn) so the regression suite can prove each value survives normalization plus a
// reread — the write path drops any update whose normalized result equals the current one, which is
// how every one of these toggles used to be a silent no-op on a fresh install.

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
        // The Tor window edits the TOR lane directly. Apps dropped from the Tor scope stay in the
        // tunnel selection (VPN lane) — leaving the Tor list must not silently unselect the app.
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

// Tor bridge settings. bridgesEnabled/bridgeTransport change the generated torrc (a live Tor
// reloads via updateRuntimeSettingAndMaybeReload); the rest is bookkeeping only.

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
