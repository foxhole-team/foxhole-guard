package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.withBlockedSelection
import com.foxhole.core.model.withLane
import com.foxhole.core.model.withTunnelSelection
import com.foxhole.core.model.withoutAssignments
import com.foxhole.guard.BuildConfig

internal fun applyRoutingModePresetTo(
    current: Settings,
    preset: RoutingModePreset,
    scope: PrivacyRouteScope,
): Settings {
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
            RoutingModePreset.VPN, RoutingModePreset.TOR, RoutingModePreset.VPN_TOR ->
                current.expert.perAppRoutingMode
        }

    val expert = current.expert
    return current.copy(

        connection =
        current.connection.copy(
            safeModeEnabled = keepsSafeModeForPreset(current, preset, perAppRoutingMode),
        ),
        traffic = current.traffic.copy(mode = TrafficMode.TUNNEL),
        expert = expert.copy(perAppRoutingMode = perAppRoutingMode),
        privacyRoute = privacyRoute,
    )
}

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

    return current.copy(
        connection =
        current.connection.copy(

            safeModeEnabled =
            current.connection.safeModeEnabled &&
                normalizedSelected.isEmpty() &&
                normalizedBlocked.isEmpty(),
        ),
        expert =
        current.expert
            .withTunnelSelection(normalizedSelected)
            .withBlockedSelection(normalizedBlocked)
            .copy(
                firewallEnabled = current.expert.firewallEnabled || normalizedBlocked.isNotEmpty(),
                blockedPackagesEnabled = normalizedBlocked.isNotEmpty(),
                blockAppsAlways = normalizedBlocked.isNotEmpty(),
            ),
    )
}

suspend fun SettingsRepository.updateAppLane(
    packageName: String,
    lane: AppTunnelLane?,
) = updateAppLanes(listOf(packageName), lane)

suspend fun SettingsRepository.updateAppLanes(
    packageNames: Collection<String>,
    lane: AppTunnelLane?,
) = update { current -> updateAppLanesIn(current, packageNames, lane) }

suspend fun SettingsRepository.applyAppLaneEdits(
    added: Collection<String>,
    lane: AppTunnelLane,
    removed: Collection<String>,
) = update { current ->
    updateAppLanesIn(updateAppLanesIn(current, removed, null), added, lane)
}

internal fun updateAppLaneIn(
    current: Settings,
    packageName: String,
    lane: AppTunnelLane?,
): Settings = updateAppLanesIn(current, listOf(packageName), lane)

internal fun updateAppLanesIn(
    current: Settings,
    packageNames: Collection<String>,
    lane: AppTunnelLane?,
): Settings {
    val normalized =
        packageNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it == BuildConfig.APPLICATION_ID }
            .distinct()
    if (normalized.isEmpty()) {
        return current
    }
    val editedExpert =
        if (lane == null) {
            current.expert.withoutAssignments(normalized)
        } else {
            current.expert.withLane(lane, normalized)
        }

    val nextExpert = editedExpert.withLane(
        AppTunnelLane.BLOCK,
        current.expert.pendingQuarantinePackages,
    )
    val blocked = nextExpert.blockedLanePackages()
    // Same rule as the batch path above: a lane assignment never rewrites the reach.
    return current.copy(
        connection =
        current.connection.copy(

            safeModeEnabled = current.connection.safeModeEnabled && nextExpert.appAssignments.isEmpty(),
        ),
        expert =
        nextExpert.copy(
            firewallEnabled = current.expert.firewallEnabled || blocked.isNotEmpty(),
            pendingQuarantinePackages = current.expert.pendingQuarantinePackages,
            pendingQuarantineAppDetails = current.expert.pendingQuarantineAppDetails,
            blockedPackagesEnabled = blocked.isNotEmpty(),
            blockAppsAlways = blocked.isNotEmpty(),
        ),
    )
}

suspend fun SettingsRepository.resolveQuarantinedApp(
    packageName: String,
    keepBlocked: Boolean,
) {
    val normalized = packageName.trim().takeIf(String::isNotBlank) ?: return
    val identity =
        if (keepBlocked) {
            runCatching { currentQuarantineIdentity(normalized) }.getOrNull()
        } else {
            requireNotNull(currentQuarantineIdentity(normalized)) {
                "quarantined application identity is unavailable"
            }
        }
    update { current -> resolveQuarantinedAppIn(current, normalized, keepBlocked, identity) }
}

internal fun resolveQuarantinedAppIn(
    current: Settings,
    packageName: String,
    keepBlocked: Boolean,
    identity: KnownApplicationIdentity? = null,
): Settings {
    val normalized = packageName.trim().takeIf(String::isNotBlank) ?: return current
    if (normalized !in current.expert.pendingQuarantinePackages) return current
    if (!keepBlocked && identity == null) return current
    val expert =
        if (keepBlocked) {
            current.expert.withLane(AppTunnelLane.BLOCK, listOf(normalized))
        } else {
            current.expert.withoutAssignments(listOf(normalized))
        }
    val blocked = expert.blockedLanePackages()
    val knownApplications =
        identity?.let { known ->
            (current.expert.quarantineKnownApplications.filterNot { it.packageName == normalized } + known)
                .sortedBy(KnownApplicationIdentity::packageName)
        } ?: current.expert.quarantineKnownApplications
    return current.copy(
        expert =
        expert.copy(
            firewallEnabled = current.expert.firewallEnabled || blocked.isNotEmpty(),
            quarantineKnownApplications = knownApplications,
            pendingQuarantinePackages = current.expert.pendingQuarantinePackages - normalized,
            pendingQuarantineAppDetails =
            current.expert.pendingQuarantineAppDetails.filterNot { details ->
                details.packageName == normalized
            },
            blockedPackagesEnabled = blocked.isNotEmpty(),
            blockAppsAlways = blocked.isNotEmpty(),
        ),
    )
}

private fun keepsSafeModeForPreset(
    current: Settings,
    preset: RoutingModePreset,
    perAppRoutingMode: PerAppRoutingMode,
): Boolean =
    current.connection.safeModeEnabled &&
        preset == RoutingModePreset.VPN &&
        perAppRoutingMode == PerAppRoutingMode.FULL_TUNNEL
