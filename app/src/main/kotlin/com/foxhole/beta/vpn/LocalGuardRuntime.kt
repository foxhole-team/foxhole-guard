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
    val countryTrafficJournalEnabled = statistics.enabled && statistics.countryTrafficEnabled
    val persistentAppBlocking =
        expert.firewallEnabled &&
            expert.blockAppsAlways &&
            expert.blockedPackagesEnabled &&
            expert.blockedPackages.any(String::isNotBlank)
    val journalFeatureEnabled =
        expert.networkActivityPersistentLogging ||
            countryTrafficJournalEnabled
    val journalEnabled = expert.firewallEnabled && journalFeatureEnabled
    return when {
        expert.systemDnsProtectionEnabled && (journalEnabled || persistentAppBlocking) -> LocalGuardMode.JOURNAL
        journalEnabled -> LocalGuardMode.JOURNAL
        persistentAppBlocking -> LocalGuardMode.FIREWALL
        expert.systemDnsProtectionEnabled -> LocalGuardMode.DNS
        else -> null
    }
}
