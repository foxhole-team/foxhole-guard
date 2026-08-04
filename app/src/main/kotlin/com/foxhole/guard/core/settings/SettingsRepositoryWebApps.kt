package com.foxhole.guard.core.settings

// Web apps: the module and dock toggles, the push watchdog and its poll interval. A file extension
// in the style of SettingsRepositoryI2p, which splits the class by domain.

suspend fun SettingsRepository.updateWebAppsEnabled(value: Boolean) =
    update { current -> current.copy(webApps = current.webApps.copy(enabled = value)) }

// Enabling push pulls the firewall in the same atomic update: the UI has already shown the consent
// form, and normalisation holds the other side of the invariant (firewall off implies push off).
suspend fun SettingsRepository.updateWebAppsPushService(value: Boolean) =
    update { current ->
        current.copy(
            webApps = current.webApps.copy(pushServiceEnabled = value),
            expert = if (value) current.expert.copy(firewallEnabled = true) else current.expert,
        )
    }

suspend fun SettingsRepository.updateWebAppsDockScreen(value: Boolean) =
    update { current -> current.copy(webApps = current.webApps.copy(dockScreenEnabled = value)) }

suspend fun SettingsRepository.updateWebAppsPollIntervalMinutes(value: Int) =
    update { current -> current.copy(webApps = current.webApps.copy(pollIntervalMinutes = value)) }
