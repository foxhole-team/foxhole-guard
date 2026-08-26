package com.foxhole.guard.core.settings

suspend fun SettingsRepository.updateWebAppsEnabled(value: Boolean) =
    update { current -> current.copy(webApps = current.webApps.copy(enabled = value)) }

suspend fun SettingsRepository.updateWebAppsPushService(value: Boolean) =
    update { current ->
        current.copy(
            webApps = current.webApps.copy(pushServiceEnabled = value),
            expert = if (value) current.expert.copy(firewallEnabled = true) else current.expert,
        )
    }

suspend fun SettingsRepository.updateWebAppsIsolation(value: Boolean) =
    update { current -> current.copy(webApps = current.webApps.copy(isolationEnabled = value)) }

suspend fun SettingsRepository.updateWebAppsDockScreen(value: Boolean) =
    update { current -> current.copy(webApps = current.webApps.copy(dockScreenEnabled = value)) }

suspend fun SettingsRepository.updateWebAppsPollIntervalMinutes(value: Int) =
    update { current -> current.copy(webApps = current.webApps.copy(pollIntervalMinutes = value)) }
