package com.foxhole.guard.core.settings

import com.foxhole.core.model.InstalledAppChangeType
import com.foxhole.core.model.InstalledAppInventoryAudit
import com.foxhole.core.model.InstalledAppInventoryChange
import com.foxhole.core.model.InstalledAppInventoryEntry
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppRiskSignal
import com.foxhole.guard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Installed-app monitoring, inventory and change auditing. Extracted from SettingsRepository
// (class split by domain).

suspend fun SettingsRepository.updateInstalledAppMonitoringEnabled(value: Boolean) =
    update { current ->
        val statistics =
            if (value) {
                current.statistics.copy(enabled = true, appChangesEnabled = true)
            } else {
                current.statistics.copy(appChangesEnabled = false)
            }
        current.copy(
            statistics = statistics,
            installedAppInventoryAudit =
            if (value) {
                current.installedAppInventoryAudit
            } else {
                InstalledAppInventoryAudit()
            },
        )
    }

suspend fun SettingsRepository.updateAppTrafficStatsEnabled(value: Boolean) =
    update { current ->
        current.copy(
            appTrafficStatsEnabled = value,
            // Settings.normalized() ANDs these two flags together on every update, so setting
            // consent in a prior, separate update() call (as onAppTrafficStatsEnabledChanged
            // does before calling this) always gets collapsed back to false there, since
            // appTrafficStatsEnabled is still false at that point. Force consent = true here,
            // atomically with appTrafficStatsEnabled, so normalization has both flags true in the
            // same pass instead of racing across transactions and permanently zeroing both.
            appTrafficUsageAccessConsent = value,
        )
    }

suspend fun SettingsRepository.updateAppTrafficUsageAccessConsent(value: Boolean) =
    update { current ->
        // Settings.normalized() always forces these two flags to be equal (it ANDs them
        // together both ways), so preserving the stats-enabled flag as-is is not actually
        // preservation: when that flag is still false (e.g. this call lands before a sibling
        // updateAppTrafficStatsEnabled(true) call finishes), normalization collapses both back
        // to false here. Force it to `value` like updateAppTrafficStatsEnabled does, so either
        // setter alone converges to the correct paired state regardless of call order.
        current.copy(
            appTrafficUsageAccessConsent = value,
            appTrafficStatsEnabled = value,
        )
    }

suspend fun SettingsRepository.recordInstalledAppInventory(
    apps: List<InstalledAppOption>,
    detectedAt: Long = System.currentTimeMillis(),
) {
    val currentSettings = current()
    if (!currentSettings.statistics.enabled || !currentSettings.statistics.appChangesEnabled) {
        return
    }
    val currentPackages =
        withContext(Dispatchers.IO) {
            apps
                .asSequence()
                .filterNot { app -> app.packageName == BuildConfig.APPLICATION_ID }
                .map(::installedAppInventoryEntry)
                .distinctBy(InstalledAppInventoryEntry::packageName)
                .sortedBy(InstalledAppInventoryEntry::packageName)
                .toList()
        }
    update { current ->
        if (!current.statistics.enabled || !current.statistics.appChangesEnabled) {
            return@update current
        }
        current.copy(
            installedAppInventoryAudit =
            buildInstalledAppInventoryAudit(
                previousAudit = current.installedAppInventoryAudit,
                currentPackages = currentPackages,
                detectedAt = detectedAt,
            ),
        )
    }
}

private fun SettingsRepository.installedAppInventoryEntry(app: InstalledAppOption): InstalledAppInventoryEntry {
    val security =
        installedAppSecurityAnalyzer.analyzePackage(
            packageName = app.packageName,
            fallbackLabel = app.label,
            fallbackIsSystemApp = app.isSystemApp,
        )
    return InstalledAppInventoryEntry(
        packageName = app.packageName,
        label = security.label.takeIf(String::isNotBlank) ?: app.label.takeIf(String::isNotBlank) ?: app.packageName,
        isSystemApp = security.isSystemApp,
        installerPackageName = security.installerPackageName,
        riskLevel = security.riskLevel,
        riskSignals = security.riskSignals,
    )
}

private fun SettingsRepository.buildInstalledAppInventoryAudit(
    previousAudit: InstalledAppInventoryAudit,
    currentPackages: List<InstalledAppInventoryEntry>,
    detectedAt: Long,
): InstalledAppInventoryAudit {
    val previousPackages = previousAudit.packages
    val changes = installedAppInventoryChanges(previousPackages, currentPackages, detectedAt)
    return InstalledAppInventoryAudit(
        capturedAt = detectedAt,
        packages = currentPackages,
        recentChanges =
        (changes + previousAudit.recentChanges)
            .distinctBy { change -> "${change.type}:${change.packageName}:${change.detectedAt}" }
            .sortedByDescending(InstalledAppInventoryChange::detectedAt)
            .take(INSTALLED_APP_CHANGE_HISTORY_LIMIT),
    )
}

private fun SettingsRepository.installedAppInventoryChanges(
    previousPackages: List<InstalledAppInventoryEntry>,
    currentPackages: List<InstalledAppInventoryEntry>,
    detectedAt: Long,
): List<InstalledAppInventoryChange> {
    if (previousPackages.isEmpty()) {
        return emptyList()
    }
    val previousByPackage = previousPackages.associateBy(InstalledAppInventoryEntry::packageName)
    val currentByPackage = currentPackages.associateBy(InstalledAppInventoryEntry::packageName)
    return buildList {
        currentPackages
            .filterNot { app -> app.packageName in previousByPackage }
            .forEach { app ->
                add(app.toInstalledAppInventoryChange(InstalledAppChangeType.INSTALLED, detectedAt))
            }
        previousPackages
            .filterNot { app -> app.packageName in currentByPackage }
            .forEach { app ->
                add(app.toInstalledAppInventoryChange(InstalledAppChangeType.REMOVED, detectedAt))
            }
    }
}

private fun InstalledAppInventoryEntry.toInstalledAppInventoryChange(
    type: InstalledAppChangeType,
    detectedAt: Long,
): InstalledAppInventoryChange =
    InstalledAppInventoryChange(
        packageName = packageName,
        label = label,
        isSystemApp = isSystemApp,
        type = type,
        detectedAt = detectedAt,
        installerPackageName = installerPackageName,
        riskLevel = riskLevel,
        riskSignals = riskSignals,
    )

suspend fun SettingsRepository.recordInstalledAppChange(
    packageName: String,
    label: String,
    isSystemApp: Boolean,
    type: InstalledAppChangeType,
    detectedAt: Long = System.currentTimeMillis(),
    installerPackageName: String? = null,
    riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
    riskSignals: List<InstalledAppRiskSignal> = emptyList(),
) = update { current ->
    if (packageName == BuildConfig.APPLICATION_ID) {
        return@update current
    }
    val normalizedPackageName = packageName.trim().takeIf(String::isNotBlank) ?: return@update current
    val normalizedLabel = label.trim().takeIf(String::isNotBlank) ?: normalizedPackageName
    val quarantineNewApp =
        type == InstalledAppChangeType.INSTALLED &&
            current.expert.newAppQuarantineEnabled &&
            !isSystemApp
    val updatedConnection =
        current.connection.copy(safeModeEnabled = current.connection.safeModeEnabled && !quarantineNewApp)
    val updatedExpert =
        when {
            quarantineNewApp -> current.expert.quarantinePackage(normalizedPackageName)
            type == InstalledAppChangeType.REMOVED -> current.expert.removeAppPackage(normalizedPackageName)
            else -> current.expert
        }
    if (!current.statistics.enabled || !current.statistics.appChangesEnabled) {
        return@update current.copy(
            connection = updatedConnection,
            expert = updatedExpert,
        )
    }
    val packageEntry =
        InstalledAppInventoryEntry(
            packageName = normalizedPackageName,
            label = normalizedLabel,
            isSystemApp = isSystemApp,
            installerPackageName = installerPackageName?.trim()?.takeIf(String::isNotBlank),
            riskLevel = riskLevel,
            riskSignals = riskSignals.distinct(),
        )
    val updatedPackages =
        when (type) {
            InstalledAppChangeType.INSTALLED ->
                (current.installedAppInventoryAudit.packages.filterNot { app -> app.packageName == normalizedPackageName } + packageEntry)
                    .sortedBy(InstalledAppInventoryEntry::packageName)
            InstalledAppChangeType.REMOVED ->
                current.installedAppInventoryAudit.packages
                    .filterNot { app -> app.packageName == normalizedPackageName }
        }
    val change =
        InstalledAppInventoryChange(
            packageName = normalizedPackageName,
            label = normalizedLabel,
            isSystemApp = isSystemApp,
            type = type,
            detectedAt = detectedAt,
            installerPackageName = packageEntry.installerPackageName,
            riskLevel = packageEntry.riskLevel,
            riskSignals = packageEntry.riskSignals,
        )
    current.copy(
        connection = updatedConnection,
        expert = updatedExpert,
        installedAppInventoryAudit =
        current.installedAppInventoryAudit.copy(
            capturedAt = detectedAt,
            packages = updatedPackages,
            recentChanges =
            (listOf(change) + current.installedAppInventoryAudit.recentChanges)
                .distinctBy { item -> "${item.type}:${item.packageName}:${item.detectedAt}" }
                .sortedByDescending(InstalledAppInventoryChange::detectedAt)
                .take(INSTALLED_APP_CHANGE_HISTORY_LIMIT),
        ),
    )
}
