package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.Settings

internal enum class LocalGuardMode {
    FIREWALL,
    JOURNAL,
    DNS,
}

internal fun LocalGuardMode.runtimeProfileName(): String =
    when (this) {
        LocalGuardMode.FIREWALL -> "Local firewall"
        LocalGuardMode.JOURNAL -> "Network journal"
        LocalGuardMode.DNS -> "System DNS protection"
    }

internal fun Settings.localGuardModeOrNull(): LocalGuardMode? {
    return when {
        localFirewallGuardRequired() -> LocalGuardMode.FIREWALL
        expert.systemDnsProtectionEnabled -> LocalGuardMode.DNS
        else -> null
    }
}

private fun Settings.localFirewallGuardRequired(): Boolean {
    if (!expert.firewallEnabled) {
        return false
    }
    val statisticsCaptureRequired =
        statistics.enabled && (
            statistics.countryTrafficEnabled ||
                statistics.anomalyMetricsEnabled && anomaly.analyzeDestinationCountries
            )
    return permanentAppBlockingRequired() ||
        expert.networkActivityPersistentLogging ||
        ui.trafficMapEnabled ||
        statisticsCaptureRequired
}

private fun Settings.permanentAppBlockingRequired(): Boolean =
    expert.blockAppsAlways &&
        expert.blockedPackagesEnabled &&
        expert.blockedPackages.any(String::isNotBlank)
