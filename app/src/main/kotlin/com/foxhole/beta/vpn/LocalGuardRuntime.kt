package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.Settings

internal enum class LocalGuardMode {
    FIREWALL,
    JOURNAL,
}

internal fun Settings.localGuardModeOrNull(): LocalGuardMode? {
    val firewallEnabled =
        expert.blockAppsAlways &&
            expert.blockedPackagesEnabled &&
            expert.blockedPackages.isNotEmpty()
    val journalEnabled =
        expert.networkActivityLogging &&
            expert.networkActivityPersistentLogging
    return when {
        journalEnabled -> LocalGuardMode.JOURNAL
        firewallEnabled -> LocalGuardMode.FIREWALL
        else -> null
    }
}

