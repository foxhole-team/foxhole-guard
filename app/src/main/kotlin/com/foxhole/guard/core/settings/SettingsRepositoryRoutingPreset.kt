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
    // Only the two split presets own the VPN's own scope. The home mode buttons pick the *Tor*
    // dimension and inherit whatever routing the user configured, for two reasons:
    //
    //  - "VPN over the whole device, Tor for selected apps" has to be expressible. Forcing
    //    INCLUDE_SELECTED_APPS for VPN_TOR narrowed the VPN itself to the Tor selection, so every
    //    other app fell out of the tunnel entirely.
    //  - Switching VPN <-> VPN+TOR <-> TOR must not silently rewrite a split the user set up in
    //    routing settings; the mode is not the place that owns it.
    val perAppRoutingMode =
        when (preset) {
            RoutingModePreset.SPLIT_INCLUDE -> PerAppRoutingMode.INCLUDE_SELECTED_APPS
            RoutingModePreset.SPLIT_EXCLUDE -> PerAppRoutingMode.EXCLUDE_SELECTED_APPS
            RoutingModePreset.VPN, RoutingModePreset.TOR, RoutingModePreset.VPN_TOR ->
                current.expert.perAppRoutingMode
        }
    // Lane membership belongs to the Apps screen alone. Repainting the whole selection to one lane
    // here destroyed a hand-built split: with some apps on VPN and others on TOR, picking a mode
    // swept every app into a single lane, so connecting the VPN moved the route onto the Tor
    // filter. A mode picks reach, never where an individual app goes.
    val expert = current.expert
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
    // Assigning a lane says WHERE an app goes when the split is in force; it must not decide
    // WHETHER the split is in force. Deriving the mode here flipped a whole-device VPN into
    // "selected apps only" the moment the first app was pinned. Whole device ignores these
    // assignments; the split honours them. Block flags still follow membership: blocking is
    // enforced under any live tunnel or the firewall, independently of reach.
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

/**
 * The new Apps screen's per-app dropdown: assign one package to [lane], or remove it from every
 * lane when [lane] is null. The block toggles, firewall arming and split mode follow from the
 * resulting lane membership, exactly as the batch [updateUnifiedAppAssignments] path derives them.
 */
suspend fun SettingsRepository.updateAppLane(
    packageName: String,
    lane: AppTunnelLane?,
) = updateAppLanes(listOf(packageName), lane)

/**
 * The picker's multi-select confirm. One settings transaction for the whole batch, not one per
 * package: a per-package write publishes every half-applied membership to the live runtime, and
 * derives the block toggles from it — so adding three apps to the block lane armed the firewall
 * after the first, and each intermediate state reached the reload path on its own.
 */
suspend fun SettingsRepository.updateAppLanes(
    packageNames: Collection<String>,
    lane: AppTunnelLane?,
) = update { current -> updateAppLanesIn(current, packageNames, lane) }

/**
 * The picker's confirm when it carries both edits at once: the apps the user ticked go to [lane],
 * the ones they un-ticked leave every lane. One transaction, removals first — two separate writes
 * would publish an intermediate membership to the live runtime (and derive the block toggles from
 * it), which is the same reason the batch above exists.
 */
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
    // A routing-picker edit is not a quarantine decision. Reassert every pending BLOCK even when
    // the edited set contains the same package; only resolveQuarantinedAppIn may remove it.
    val nextExpert = editedExpert.withLane(
        AppTunnelLane.BLOCK,
        current.expert.pendingQuarantinePackages,
    )
    val blocked = nextExpert.blockedLanePackages()
    // Same rule as the batch path above: a lane assignment never rewrites the reach.
    return current.copy(
        connection =
        current.connection.copy(
            // Pinning a package to ANY lane (not just BLOCK) leaves safe mode: while safe mode is
            // on, Settings.normalized() drops every non-BLOCK lane, so the old `blocked.isEmpty()`
            // clause made VPN/TOR/EXCLUDE assignments silently vanish on write.
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

/** Completes the explicit decision for an app that was blocked automatically on install. */
suspend fun SettingsRepository.resolveQuarantinedApp(
    packageName: String,
    keepBlocked: Boolean,
) {
    val normalized = packageName.trim().takeIf(String::isNotBlank) ?: return
    val identity =
        if (keepBlocked) {
            runCatching { currentQuarantineIdentity(normalized) }.getOrNull()
        } else {
            // Releasing a package without pinning its current identity would turn a package-name
            // decision into a signature bypass. A PackageManager failure therefore aborts the
            // decision and leaves the native quarantine armed.
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
