package com.foxhole.guard.core.settings

import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.Settings
import com.foxhole.core.model.blockedLanePackages

suspend fun SettingsRepository.updateNewAppQuarantineEnabled(value: Boolean) {
    val before = current()
    val newlyEnabled = value && !before.expert.newAppQuarantineEnabled
    val capturedBaseline = quarantineBaselineForUpdate(value, before)
    update { current ->
        current.withNewAppQuarantineEnabled(value, capturedBaseline)
    }
    if (value) {
        reconcileEnabledQuarantineOrRollback(newlyEnabled)
    }
}

private suspend fun SettingsRepository.quarantineBaselineForUpdate(
    enabled: Boolean,
    before: Settings,
): List<KnownApplicationIdentity> =
    if (before.requiresFreshQuarantineBaseline(enabled)) {
        captureKnownApplicationsForQuarantine()
    } else {
        before.expert.quarantineKnownApplications
    }

private fun Settings.requiresFreshQuarantineBaseline(enabled: Boolean): Boolean =
    enabled &&
        (!expert.newAppQuarantineEnabled || expert.quarantineKnownApplications.isEmpty())

private fun Settings.withNewAppQuarantineEnabled(
    enabled: Boolean,
    capturedBaseline: List<KnownApplicationIdentity>,
): Settings {
    val baseline =
        when {
            !enabled -> emptyList()
            expert.newAppQuarantineEnabled && expert.quarantineKnownApplications.isNotEmpty() ->
                expert.quarantineKnownApplications
            else -> capturedBaseline
        }
    val blockedLanePresent = expert.blockedLanePackages().isNotEmpty()
    return copy(
        connection = connection.copy(safeModeEnabled = connection.safeModeEnabled && !enabled),
        expert =
        expert.copy(
            newAppQuarantineEnabled = enabled,
            quarantineKnownApplications = baseline,
            firewallEnabled = expert.firewallEnabled || enabled,
            blockedPackagesEnabled = expert.blockedPackagesEnabled || (enabled && blockedLanePresent),
            blockAppsAlways = expert.blockAppsAlways || (enabled && blockedLanePresent),
        ),
    )
}

private suspend fun SettingsRepository.reconcileEnabledQuarantineOrRollback(newlyEnabled: Boolean) {
    runCatching { reconcileNewAppQuarantineGaps() }
        .onFailure {
            if (newlyEnabled) {
                update { current ->
                    current.copy(
                        expert =
                        current.expert.copy(
                            newAppQuarantineEnabled = false,
                            quarantineKnownApplications = emptyList(),
                        ),
                    )
                }
            }
        }.getOrThrow()
}

suspend fun SettingsRepository.ensureNewAppQuarantineBaseline(settings: Settings? = null): Settings {
    val currentSettings = settings ?: current()
    if (
        !currentSettings.expert.newAppQuarantineEnabled ||
        currentSettings.expert.quarantineKnownApplications.isNotEmpty()
    ) {
        return currentSettings
    }
    val capturedBaseline = captureKnownApplicationsForQuarantine()
    update { current ->
        if (current.expert.newAppQuarantineEnabled && current.expert.quarantineKnownApplications.isEmpty()) {
            current.copy(
                expert = current.expert.copy(quarantineKnownApplications = capturedBaseline),
            )
        } else {
            current
        }
    }
    return current()
}
