package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.excludedLanePackages
import com.foxhole.core.model.packages
import com.foxhole.core.model.tunnelSelectedPackages

internal fun ExpertSettings.vpnIncludedPackages(): List<String> =
    when (perAppRoutingMode) {
        PerAppRoutingMode.INCLUDE_SELECTED_APPS ->
            normalizedRuntimePackages(
                tunnelSelectedPackages() +
                    blockedLanePackages().takeIf { blockedPackagesEnabled }.orEmpty(),
            )
        PerAppRoutingMode.FULL_TUNNEL,
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
        -> emptyList()
    }

internal fun ExpertSettings.vpnExcludedPackages(): List<String> =
    when (perAppRoutingMode) {
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS ->
            normalizedRuntimePackages(tunnelSelectedPackages() + excludedLanePackages())
        PerAppRoutingMode.FULL_TUNNEL,
        PerAppRoutingMode.INCLUDE_SELECTED_APPS,
        -> normalizedRuntimePackages(excludedLanePackages())
    }

internal fun ExpertSettings.torLanePackages(): List<String> =
    normalizedRuntimePackages(packages(AppTunnelLane.TOR))

internal fun ExpertSettings.vpnLanePackages(): List<String> =
    normalizedRuntimePackages(packages(AppTunnelLane.VPN))

internal fun ExpertSettings.failClosedBlockPackages(
    torLaneCarried: Boolean,
    vpnLaneCarried: Boolean,
): List<String> =
    normalizedRuntimePackages(
        torLanePackages().takeUnless { torLaneCarried }.orEmpty() +
            vpnLanePackages().takeUnless { vpnLaneCarried }.orEmpty(),
    )

internal fun normalizedRuntimePackages(packageNames: List<String>): List<String> =
    packageNames
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
