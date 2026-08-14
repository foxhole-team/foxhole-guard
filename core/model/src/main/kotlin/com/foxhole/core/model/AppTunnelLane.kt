package com.foxhole.core.model

import kotlinx.serialization.Serializable

/**
 * The single per-app routing decision. Every package the user pins has exactly one lane, so the
 * old parallel "selected"/"blocked" lists collapse into one [ExpertSettings.appAssignments] map:
 *
 * - [TOR]     route this app's traffic through the Tor circuit;
 * - [VPN]     route it through the active VPN/proxy tunnel (the ordinary tunnelled app);
 * - [BLOCK]   deny it the network (firewall), inside a profile tunnel and under the local guard;
 * - [EXCLUDE] keep it outside the tunnel entirely — no DNS filtering, no anomaly inspection.
 */
@Serializable
enum class AppTunnelLane {
    TOR,
    VPN,
    BLOCK,
    EXCLUDE,
}

/** Packages currently assigned to [lane], deterministically ordered. */
fun ExpertSettings.packages(lane: AppTunnelLane): List<String> =
    appAssignments.asSequence()
        .filter { (_, assigned) -> assigned == lane }
        .map { (packageName, _) -> packageName }
        .sorted()
        .toList()

/**
 * Apps individually pinned to the tunnel — Tor- or VPN-routed. This is the per-app split
 * selection (what the old `selectedPackages` list held): both lanes ride the tunnel's include /
 * exclude set; the Tor-vs-VPN distinction only diverges in route assembly.
 */
fun ExpertSettings.tunnelSelectedPackages(): List<String> =
    (packages(AppTunnelLane.TOR) + packages(AppTunnelLane.VPN)).sorted()

/** Firewall-blocked apps — the [AppTunnelLane.BLOCK] lane. */
fun ExpertSettings.blockedLanePackages(): List<String> = packages(AppTunnelLane.BLOCK)

/** Apps kept outside the tunnel (no filtering, no inspection) — the [AppTunnelLane.EXCLUDE] lane. */
fun ExpertSettings.excludedLanePackages(): List<String> = packages(AppTunnelLane.EXCLUDE)

/**
 * Every app the tunnel really keeps outside itself in the mode these settings are in.
 *
 * The [AppTunnelLane.EXCLUDE] lane is out in every mode. In exclude mode the *selection* is out
 * too — that is what "everything except the selected apps" means — so an app pinned to the VPN
 * lane there is not tunnelled, it is the exception. The Tor lane is the one part of the selection
 * that stays inside: the runtime keeps Tor-pinned apps in the tun so their route rule can match at
 * all (`RuntimeTunInbound` removes them from the exclusion), and listing them as excluded would be
 * the opposite of what happens.
 *
 * Shared with the runtime's own split plan on purpose: a screen that decided this for itself is a
 * screen that can disagree with the tunnel it is describing.
 */
fun ExpertSettings.tunnelKeptOutPackages(): List<String> =
    when (perAppRoutingMode) {
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS ->
            (packages(AppTunnelLane.VPN) + excludedLanePackages()).distinct().sorted()
        PerAppRoutingMode.FULL_TUNNEL,
        PerAppRoutingMode.INCLUDE_SELECTED_APPS,
        -> excludedLanePackages()
    }

/** Copies these settings with [packageNames] assigned to [lane], dropping any prior lane they held. */
fun ExpertSettings.withLane(
    lane: AppTunnelLane,
    packageNames: Collection<String>,
): ExpertSettings {
    val pinned = packageNames.toSet()
    val retained = appAssignments.filterKeys { it !in pinned }
    return copy(appAssignments = retained + pinned.associateWith { lane })
}

/** Copies these settings with [packageNames] removed from every lane. */
fun ExpertSettings.withoutAssignments(packageNames: Collection<String>): ExpertSettings {
    val removed = packageNames.toSet()
    return copy(appAssignments = appAssignments.filterKeys { it !in removed })
}

/**
 * True when the Tor route has something to carry: the whole device, or a non-empty TOR lane.
 * The inverse is the "select apps first" guard shared by every Tor entry point.
 */
fun Settings.torScopeRunnable(): Boolean =
    when (privacyRoute.scope) {
        PrivacyRouteScope.ALL_APPS -> true
        PrivacyRouteScope.SELECTED_APPS -> expert.packages(AppTunnelLane.TOR).isNotEmpty()
    }

/**
 * Replaces the tunnel-selected set (Tor + VPN lanes) with [packageNames]: each keeps an existing
 * Tor lane and otherwise defaults to VPN, and a newly-selected app leaves the block / exclude
 * lanes (a routed app must not stay silently firewalled or bypassed). Packages no longer selected
 * lose their routing lane; block / exclude assignments of untouched packages stay put.
 */
fun ExpertSettings.withTunnelSelection(packageNames: Collection<String>): ExpertSettings {
    val selected = packageNames.toSet()
    val next = appAssignments.toMutableMap()
    next.entries.removeAll { (pkg, lane) ->
        (lane == AppTunnelLane.TOR || lane == AppTunnelLane.VPN) && pkg !in selected
    }
    selected.forEach { pkg ->
        next[pkg] = if (appAssignments[pkg] == AppTunnelLane.TOR) AppTunnelLane.TOR else AppTunnelLane.VPN
    }
    return copy(appAssignments = next)
}

/**
 * Replaces the firewall block set with [packageNames]: newly-blocked apps leave any routing lane,
 * and packages no longer blocked lose the block lane. Tor / VPN / exclude assignments of untouched
 * packages stay put.
 */
fun ExpertSettings.withBlockedSelection(packageNames: Collection<String>): ExpertSettings {
    val blocked = packageNames.toSet()
    val next = appAssignments.toMutableMap()
    next.entries.removeAll { (pkg, lane) -> lane == AppTunnelLane.BLOCK && pkg !in blocked }
    blocked.forEach { pkg -> next[pkg] = AppTunnelLane.BLOCK }
    return copy(appAssignments = next)
}
