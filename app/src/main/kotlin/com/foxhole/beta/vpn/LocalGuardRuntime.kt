package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.Settings

internal enum class LocalGuardMode {
    FIREWALL,
    JOURNAL,
}

internal fun Settings.localGuardModeOrNull(): LocalGuardMode? {
    val firewallEnabled =
        expert.killSwitchEnabled ||
            (
                expert.blockAppsAlways &&
                    expert.blockedPackagesEnabled &&
                    expert.blockedPackages.isNotEmpty()
            )
    val journalEnabled =
        expert.networkActivityLogging &&
            expert.networkActivityPersistentLogging
    return when {
        firewallEnabled -> LocalGuardMode.FIREWALL
        journalEnabled -> LocalGuardMode.JOURNAL
        else -> null
    }
}
