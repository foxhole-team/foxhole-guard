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
            // Never leave a newly enabled quarantine with a baseline whose enable-time race could
            // not be closed. Any already-persisted pending blocks remain fail-closed.
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

/**
 * One-shot compatibility migration for settings written before the persisted quarantine baseline
 * existed. A non-empty baseline is never refreshed here: doing so would admit apps installed while
 * the tunnel was down.
 */
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
