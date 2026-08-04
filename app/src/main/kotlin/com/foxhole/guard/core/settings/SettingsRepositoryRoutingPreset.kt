package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.packages
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.core.model.withBlockedSelection
import com.foxhole.core.model.withLane
import com.foxhole.core.model.withTunnelSelection
import com.foxhole.core.model.withoutAssignments
import com.foxhole.guard.BuildConfig

// The single app-selection area and the MODE-button facade. One selected-apps
// set drives every scoped mode (Split's include/exclude and Tor's selected-apps — "the apps you
// already picked"); the firewall block list stays a separate per-app flag beside it.

/**
 * Maps a MODE-button preset onto the underlying settings axes. Deliberately does NOT touch
 * `privacyRoute.permitted` (the Tor consent gate stays with its own UI flow) and does not clear
 * the shared selection — switching modes must never lose the user's picks.
 */
internal fun applyRoutingModePresetTo(
    current: Settings,
    preset: RoutingModePreset,
    scope: PrivacyRouteScope,
): Settings {
    val torPreset = preset == RoutingModePreset.TOR || preset == RoutingModePreset.VPN_TOR
    val privacyRoute =
        when (preset) {
            RoutingModePreset.VPN, RoutingModePreset.SPLIT_INCLUDE, RoutingModePreset.SPLIT_EXCLUDE ->
                current.privacyRoute.copy(mode = PrivacyRouteMode.OFF)
            RoutingModePreset.TOR ->
                current.privacyRoute.copy(
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    bypassVpnTunnel = true,
                    scope = scope,
                )
            RoutingModePreset.VPN_TOR ->
                current.privacyRoute.copy(
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    bypassVpnTunnel = false,
                    scope = scope,
                )
        }
    val perAppRoutingMode =
        when (preset) {
            RoutingModePreset.SPLIT_INCLUDE -> PerAppRoutingMode.INCLUDE_SELECTED_APPS
            RoutingModePreset.SPLIT_EXCLUDE -> PerAppRoutingMode.EXCLUDE_SELECTED_APPS
            RoutingModePreset.VPN, RoutingModePreset.TOR -> PerAppRoutingMode.FULL_TUNNEL
            RoutingModePreset.VPN_TOR ->
                if (scope == PrivacyRouteScope.SELECTED_APPS) {
                    PerAppRoutingMode.INCLUDE_SELECTED_APPS
                } else {
                    PerAppRoutingMode.FULL_TUNNEL
                }
        }
    // The preset owns the Tor-vs-VPN colour of the whole tunnel selection: a Tor preset over
    // selected apps promotes the selection to the TOR lane, a Tor-free preset returns it to VPN
    // (the apps stay selected either way — switching modes must never lose the user's picks).
    val expert =
        if (torPreset && scope == PrivacyRouteScope.SELECTED_APPS) {
            current.expert.withLane(AppTunnelLane.TOR, current.expert.tunnelSelectedPackages())
        } else if (!torPreset) {
            current.expert.withLane(AppTunnelLane.VPN, current.expert.packages(AppTunnelLane.TOR))
        } else {
            current.expert
        }
    return current.copy(
        // Picking a MODE is an explicit expert customization, so safe mode has to end here. It is on
        // by default, and update() runs Settings.normalized() BEFORE persisting — with safe mode set
        // that rebuilds ExpertSettings without perAppRoutingMode (back to FULL_TUNNEL), keeps only
        // BLOCK lane assignments and resets the whole privacyRoute block. The stripped result then
        // usually equals the previous state, so update()'s `next == current` early-return skipped the
        // write entirely and the choice was silently lost on relaunch. Only a plain full-tunnel VPN
        // preset — which customizes nothing — may stay in safe mode.
        connection =
        current.connection.copy(
            safeModeEnabled = keepsSafeModeForPreset(current, preset, perAppRoutingMode),
        ),
        traffic = current.traffic.copy(mode = TrafficMode.TUNNEL),
        expert = expert.copy(perAppRoutingMode = perAppRoutingMode),
        privacyRoute = privacyRoute,
    )
}

/**
 * The MODE-button preset the current settings represent — the inverse of [applyRoutingModePresetTo].
 * Used to decide, on a live tunnel, whether a requested mode change attaches/detaches Tor or drops
 * the VPN, so the right confirmation modal is shown.
 */
fun Settings.activeRoutingModePreset(): RoutingModePreset =
    when {
        privacyRoute.enabled && privacyRoute.bypassVpnTunnel -> RoutingModePreset.TOR
        privacyRoute.enabled -> RoutingModePreset.VPN_TOR
        expert.perAppRoutingMode == PerAppRoutingMode.INCLUDE_SELECTED_APPS -> RoutingModePreset.SPLIT_INCLUDE
        expert.perAppRoutingMode == PerAppRoutingMode.EXCLUDE_SELECTED_APPS -> RoutingModePreset.SPLIT_EXCLUDE
        else -> RoutingModePreset.VPN
    }

suspend fun SettingsRepository.applyRoutingModePreset(
    preset: RoutingModePreset,
    scope: PrivacyRouteScope = PrivacyRouteScope.ALL_APPS,
) = update { current -> applyRoutingModePresetTo(current, preset, scope) }

/**
 * Atomically writes the two mutually exclusive lanes exposed by the new Apps screen. A routed app
 * is shared by Split and Tor; a blocked app is removed from that route and is denied by the
 * firewall both inside a profile TUN and while the local firewall TUN is the only carrier.
 *
 * Enabling the block lane also enables the firewall. Removing the final blocked app deliberately
 * does not turn a manually enabled firewall off.
 */
suspend fun SettingsRepository.updateUnifiedAppAssignments(
    selectedPackages: List<String>,
    blockedPackages: List<String>,
) = update { current -> updateUnifiedAppAssignmentsIn(current, selectedPackages, blockedPackages) }

internal fun updateUnifiedAppAssignmentsIn(
    current: Settings,
    selectedPackages: List<String>,
    blockedPackages: List<String>,
): Settings {
    val normalizedBlocked =
        blockedPackages
            .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
            .distinct()
    val normalizedSelected =
        selectedPackages
            .filterNot { packageName ->
                packageName == BuildConfig.APPLICATION_ID || packageName in normalizedBlocked
            }
            .distinct()
    val nextMode = selectedPackagesRoutingMode(current.expert.perAppRoutingMode, normalizedSelected)
    return current.copy(
        connection =
        current.connection.copy(
            // Any explicit lane assignment is an expert customization: safe mode must end here,
            // or Settings.normalized() strips the routing lanes right back out of the write.
            safeModeEnabled =
            current.connection.safeModeEnabled &&
                normalizedSelected.isEmpty() &&
                normalizedBlocked.isEmpty(),
        ),
        traffic = current.traffic.copy(mode = splitTunnelTrafficMode(current.traffic.mode, nextMode)),
        expert =
        current.expert
            .withTunnelSelection(normalizedSelected)
            .withBlockedSelection(normalizedBlocked)
            .copy(
                firewallEnabled = current.expert.firewallEnabled || normalizedBlocked.isNotEmpty(),
                perAppRoutingMode = nextMode,
                blockedPackagesEnabled = normalizedBlocked.isNotEmpty(),
                blockAppsAlways = normalizedBlocked.isNotEmpty(),
            ),
    )
}

/**
 * The new Apps screen's per-app dropdown: assign one package to [lane], or remove it from every
 * lane when [lane] is null. The block toggles, firewall arming and split mode follow from the
 * resulting lane membership, exactly as the batch [updateUnifiedAppAssignments] path derives them.
 */
suspend fun SettingsRepository.updateAppLane(
    packageName: String,
    lane: AppTunnelLane?,
) = update { current -> updateAppLaneIn(current, packageName, lane) }

internal fun updateAppLaneIn(
    current: Settings,
    packageName: String,
    lane: AppTunnelLane?,
): Settings {
    val normalized = packageName.trim().takeIf(String::isNotBlank) ?: return current
    if (normalized == BuildConfig.APPLICATION_ID) {
        return current
    }
    val nextExpert =
        if (lane == null) {
            current.expert.withoutAssignments(listOf(normalized))
        } else {
            current.expert.withLane(lane, listOf(normalized))
        }
    val blocked = nextExpert.blockedLanePackages()
    val nextMode = selectedPackagesRoutingMode(current.expert.perAppRoutingMode, nextExpert.tunnelSelectedPackages())
    return current.copy(
        connection =
        current.connection.copy(
            // Pinning a package to ANY lane (not just BLOCK) leaves safe mode: while safe mode is
            // on, Settings.normalized() drops every non-BLOCK lane, so the old `blocked.isEmpty()`
            // clause made VPN/TOR/EXCLUDE assignments silently vanish on write.
            safeModeEnabled = current.connection.safeModeEnabled && nextExpert.appAssignments.isEmpty(),
        ),
        traffic = current.traffic.copy(mode = splitTunnelTrafficMode(current.traffic.mode, nextMode)),
        expert =
        nextExpert.copy(
            firewallEnabled = current.expert.firewallEnabled || blocked.isNotEmpty(),
            perAppRoutingMode = nextMode,
            pendingQuarantinePackages = current.expert.pendingQuarantinePackages - normalized,
            blockedPackagesEnabled = blocked.isNotEmpty(),
            blockAppsAlways = blocked.isNotEmpty(),
        ),
    )
}

/** Completes the explicit decision for an app that was blocked automatically on install. */
suspend fun SettingsRepository.resolveQuarantinedApp(
    packageName: String,
    keepBlocked: Boolean,
) = update { current -> resolveQuarantinedAppIn(current, packageName, keepBlocked) }

internal fun resolveQuarantinedAppIn(
    current: Settings,
    packageName: String,
    keepBlocked: Boolean,
): Settings {
    val normalized = packageName.trim().takeIf(String::isNotBlank) ?: return current
    val expert =
        if (keepBlocked) {
            current.expert.withLane(AppTunnelLane.BLOCK, listOf(normalized))
        } else {
            current.expert.withoutAssignments(listOf(normalized))
        }
    val blocked = expert.blockedLanePackages()
    return current.copy(
        expert =
        expert.copy(
            firewallEnabled = current.expert.firewallEnabled || blocked.isNotEmpty(),
            pendingQuarantinePackages = current.expert.pendingQuarantinePackages - normalized,
            blockedPackagesEnabled = blocked.isNotEmpty(),
            blockAppsAlways = blocked.isNotEmpty(),
        ),
    )
}

/**
 * Only a plain full-tunnel VPN preset customizes nothing, so only it may remain in safe mode. Any
 * other MODE choice must clear it: update() applies Settings.normalized() BEFORE persisting, and with
 * safe mode set that rebuilds ExpertSettings without perAppRoutingMode, keeps only BLOCK lane
 * assignments and resets privacyRoute — the stripped result then usually equals the previous state,
 * so update()'s `next == current` early-return dropped the write and the choice never reached disk.
 */
private fun keepsSafeModeForPreset(
    current: Settings,
    preset: RoutingModePreset,
    perAppRoutingMode: PerAppRoutingMode,
): Boolean =
    current.connection.safeModeEnabled &&
        preset == RoutingModePreset.VPN &&
        perAppRoutingMode == PerAppRoutingMode.FULL_TUNNEL
