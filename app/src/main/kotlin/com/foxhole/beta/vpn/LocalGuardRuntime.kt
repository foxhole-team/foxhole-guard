package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.Settings

internal enum class LocalGuardMode {
    FIREWALL,
    JOURNAL,
}

internal fun Settings.localGuardModeOrNull(): LocalGuardMode? {
    val permanentAppBlockingEnabled =
        expert.firewallEnabled &&
            expert.blockAppsAlways &&
            expert.blockedPackagesEnabled &&
            expert.blockedPackages.isNotEmpty()
    val firewallEnabled = permanentAppBlockingEnabled
    val journalEnabled = expert.firewallEnabled
    return when {
        firewallEnabled -> LocalGuardMode.FIREWALL
        journalEnabled -> LocalGuardMode.JOURNAL
        else -> null
    }
}
