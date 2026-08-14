package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.excludedLanePackages
import com.foxhole.core.model.packages
import com.foxhole.core.model.tunnelSelectedPackages

// Per-app package selection helpers shared by TUN inbound + route assembly.
// Lane semantics: INCLUDE split carries the Tor/VPN lanes (+ blocked apps so the firewall rule
// can see and drop them); EXCLUDE split keeps the selection OUT of the tun. The EXCLUDE lane is
// out of the tun in every mode — no filtering, no inspection — except INCLUDE, where it simply
// is not part of the include set.

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

/** The Tor lane as the runtime consumes it (trimmed, deduplicated, sorted). */
internal fun ExpertSettings.torLanePackages(): List<String> =
    normalizedRuntimePackages(packages(AppTunnelLane.TOR))

internal fun normalizedRuntimePackages(packageNames: List<String>): List<String> =
    packageNames
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
