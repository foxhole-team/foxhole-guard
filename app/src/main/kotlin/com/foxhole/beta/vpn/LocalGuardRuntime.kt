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
    val permanentAppBlockingEnabled =
        expert.firewallEnabled &&
            expert.blockAppsAlways &&
            expert.blockedPackagesEnabled &&
            expert.blockedPackages.isNotEmpty()
    val journalEnabled =
        expert.firewallEnabled &&
            (
                expert.networkActivityPersistentLogging ||
                    (statistics.enabled && statistics.countryTrafficEnabled)
            )
    return when {
        permanentAppBlockingEnabled && expert.systemDnsProtectionEnabled -> LocalGuardMode.JOURNAL
        permanentAppBlockingEnabled -> LocalGuardMode.FIREWALL
        journalEnabled -> LocalGuardMode.JOURNAL
        expert.systemDnsProtectionEnabled -> LocalGuardMode.DNS
        else -> null
    }
}
