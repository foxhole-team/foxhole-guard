package com.foxhole.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AppTunnelLane {
    TOR,
    VPN,
    BLOCK,
    EXCLUDE,
}

fun ExpertSettings.packages(lane: AppTunnelLane): List<String> =
    appAssignments.asSequence()
        .filter { (_, assigned) -> assigned == lane }
        .map { (packageName, _) -> packageName }
        .sorted()
        .toList()

fun ExpertSettings.tunnelSelectedPackages(): List<String> =
    (packages(AppTunnelLane.TOR) + packages(AppTunnelLane.VPN)).sorted()

fun ExpertSettings.blockedLanePackages(): List<String> = packages(AppTunnelLane.BLOCK)

fun ExpertSettings.excludedLanePackages(): List<String> = packages(AppTunnelLane.EXCLUDE)

fun ExpertSettings.tunnelKeptOutPackages(): List<String> =
    when (perAppRoutingMode) {
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS ->
            (packages(AppTunnelLane.VPN) + excludedLanePackages()).distinct().sorted()
        PerAppRoutingMode.FULL_TUNNEL,
        PerAppRoutingMode.INCLUDE_SELECTED_APPS,
        -> excludedLanePackages()
    }

fun ExpertSettings.withLane(
    lane: AppTunnelLane,
    packageNames: Collection<String>,
): ExpertSettings {
    val pinned = packageNames.toSet()
    val retained = appAssignments.filterKeys { it !in pinned }
    return copy(appAssignments = retained + pinned.associateWith { lane })
}

fun ExpertSettings.withoutAssignments(packageNames: Collection<String>): ExpertSettings {
    val removed = packageNames.toSet()
    return copy(appAssignments = appAssignments.filterKeys { it !in removed })
}

fun Settings.torScopeRunnable(): Boolean =
    when (privacyRoute.scope) {
        PrivacyRouteScope.ALL_APPS -> true
        PrivacyRouteScope.SELECTED_APPS -> expert.packages(AppTunnelLane.TOR).isNotEmpty()
    }

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

fun ExpertSettings.withBlockedSelection(packageNames: Collection<String>): ExpertSettings {
    val blocked = packageNames.toSet()
    val next = appAssignments.toMutableMap()
    next.entries.removeAll { (pkg, lane) -> lane == AppTunnelLane.BLOCK && pkg !in blocked }
    blocked.forEach { pkg -> next[pkg] = AppTunnelLane.BLOCK }
    return copy(appAssignments = next)
}
