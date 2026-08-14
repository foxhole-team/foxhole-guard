package com.foxhole.guard.core.settings

import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.InstalledAppChangeType
import com.foxhole.core.model.InstalledAppInventoryAudit
import com.foxhole.core.model.InstalledAppInventoryChange
import com.foxhole.core.model.InstalledAppInventoryEntry
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppRiskSignal
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.PendingQuarantineAppDetails
import com.foxhole.core.model.Settings
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
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
            // This is the explicit user toggle, so it is also the consent boundary. Live Android
            // Usage Access is checked separately and never writes either stored preference.
            appTrafficUsageAccessConsent = value,
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
    firstInstallTime: Long? = null,
    installerPackageName: String? = null,
    riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
    riskSignals: List<InstalledAppRiskSignal> = emptyList(),
    quarantineIdentity: KnownApplicationIdentity? = null,
    quarantineAnalysisComplete: Boolean = true,
) = update { current ->
    recordInstalledAppChangeIn(
        current = current,
        packageName = packageName,
        label = label,
        isSystemApp = isSystemApp,
        type = type,
        detectedAt = detectedAt,
        firstInstallTime = firstInstallTime,
        installerPackageName = installerPackageName,
        riskLevel = riskLevel,
        riskSignals = riskSignals,
        quarantineIdentity = quarantineIdentity,
        quarantineAnalysisComplete = quarantineAnalysisComplete,
    )
}

internal fun recordInstalledAppChangeIn(
    current: Settings,
    packageName: String,
    label: String,
    isSystemApp: Boolean,
    type: InstalledAppChangeType,
    detectedAt: Long,
    firstInstallTime: Long? = null,
    installerPackageName: String? = null,
    riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
    riskSignals: List<InstalledAppRiskSignal> = emptyList(),
    quarantineIdentity: KnownApplicationIdentity? = null,
    quarantineAnalysisComplete: Boolean = true,
): Settings {
    if (packageName == BuildConfig.APPLICATION_ID) {
        return current
    }
    val normalizedPackageName = packageName.trim().takeIf(String::isNotBlank) ?: return current
    val normalizedLabel = label.trim().takeIf(String::isNotBlank) ?: normalizedPackageName
    val quarantineNewApp =
        type == InstalledAppChangeType.INSTALLED &&
            current.expert.newAppQuarantineEnabled &&
            !isSystemApp
    val updatedConnection =
        current.connection.copy(safeModeEnabled = current.connection.safeModeEnabled && !quarantineNewApp)
    val updatedExpert =
        current.expert.afterInstalledAppChange(
            packageName = normalizedPackageName,
            type = type,
            isSystemApp = isSystemApp,
            quarantineNewApp = quarantineNewApp,
            quarantineIdentity = quarantineIdentity,
            quarantineDetails = if (quarantineNewApp) {
                PendingQuarantineAppDetails(
                    packageName = normalizedPackageName,
                    label = normalizedLabel,
                    firstInstallTime = firstInstallTime?.takeIf { value -> value > 0L },
                    detectedAt = detectedAt,
                    installerPackageName = installerPackageName?.trim()?.takeIf(String::isNotBlank),
                    riskLevel = riskLevel,
                    riskSignals = riskSignals.distinct(),
                    analysisComplete = quarantineAnalysisComplete,
                )
            } else {
                null
            },
        )
    if (!current.statistics.enabled || !current.statistics.appChangesEnabled) {
        return current.copy(
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
    return current.copy(
        connection = updatedConnection,
        expert = updatedExpert,
        installedAppInventoryAudit =
        current.installedAppInventoryAudit.withInstalledAppChange(
            entry = packageEntry,
            type = type,
            detectedAt = detectedAt,
        ),
    )
}

internal data class QuarantineEnrichmentOutcome(
    val pendingAfterCommit: Boolean,
    val routeChanged: Boolean,
    val analysisCompletedNow: Boolean = false,
)

internal data class InstalledAppChangeEnrichment(
    val settings: Settings,
    val outcome: QuarantineEnrichmentOutcome,
)

/**
 * Replaces preliminary install facts without ever creating a quarantine decision. The pending
 * membership check and the write share the repository lock, so a late FoxHole Sentinel result
 * cannot undo an Allow/Block decision or resurrect a package that was removed meanwhile.
 */
internal suspend fun SettingsRepository.enrichInstalledAppChange(
    packageName: String,
    detectedAt: Long,
    firstInstallTime: Long?,
    label: String,
    isSystemApp: Boolean,
    installerPackageName: String?,
    riskLevel: InstalledAppRiskLevel,
    riskSignals: List<InstalledAppRiskSignal>,
    installedIdentity: KnownApplicationIdentity?,
): QuarantineEnrichmentOutcome {
    var outcome = QuarantineEnrichmentOutcome(pendingAfterCommit = false, routeChanged = false)
    update { current ->
        enrichInstalledAppChangeIn(
            current = current,
            packageName = packageName,
            detectedAt = detectedAt,
            firstInstallTime = firstInstallTime,
            label = label,
            isSystemApp = isSystemApp,
            installerPackageName = installerPackageName,
            riskLevel = riskLevel,
            riskSignals = riskSignals,
            installedIdentity = installedIdentity,
        ).also { enrichment -> outcome = enrichment.outcome }.settings
    }
    return outcome
}

internal fun enrichInstalledAppChangeIn(
    current: Settings,
    packageName: String,
    detectedAt: Long,
    firstInstallTime: Long?,
    label: String,
    isSystemApp: Boolean,
    installerPackageName: String?,
    riskLevel: InstalledAppRiskLevel,
    riskSignals: List<InstalledAppRiskSignal>,
    installedIdentity: KnownApplicationIdentity?,
): InstalledAppChangeEnrichment {
    val normalizedPackageName = packageName.trim().takeIf(String::isNotBlank)
        ?: return InstalledAppChangeEnrichment(
            settings = current,
            outcome = QuarantineEnrichmentOutcome(pendingAfterCommit = false, routeChanged = false),
        )
    val existingDetails =
        current.expert.pendingQuarantineAppDetails
            .firstOrNull { details -> details.packageName == normalizedPackageName }
    val sameInstallation =
        firstInstallTime == null ||
            installedIdentity?.firstSeenAtMs == null ||
            installedIdentity.firstSeenAtMs == firstInstallTime
    val pendingBefore =
        existingDetails.matchesPendingEnrichment(
            packageName = normalizedPackageName,
            detectedAt = detectedAt,
            pendingPackages = current.expert.pendingQuarantinePackages,
            sameInstallation = sameInstallation,
        )
    val enrichedDetails =
        PendingQuarantineAppDetails(
            packageName = normalizedPackageName,
            label = label.trim().ifBlank { normalizedPackageName },
            firstInstallTime = existingDetails?.firstInstallTime ?: firstInstallTime?.takeIf { value -> value > 0L },
            detectedAt = existingDetails?.detectedAt ?: detectedAt,
            installerPackageName = installerPackageName?.trim()?.takeIf(String::isNotBlank),
            riskLevel = riskLevel,
            riskSignals = riskSignals.distinct(),
            analysisComplete = true,
        )
    val expert =
        current.expert.afterInstalledAppEnrichment(
            packageName = normalizedPackageName,
            pendingBefore = pendingBefore,
            isSystemApp = isSystemApp,
            installedIdentity = installedIdentity,
            enrichedDetails = enrichedDetails,
        )
    val pendingAfter = normalizedPackageName in expert.pendingQuarantinePackages
    val outcome =
        QuarantineEnrichmentOutcome(
            pendingAfterCommit = pendingBefore && pendingAfter,
            routeChanged = pendingBefore && !pendingAfter,
            analysisCompletedNow = pendingBefore,
        )
    val inventoryContainsPackage =
        current.installedAppInventoryAudit.packages.any { entry -> entry.packageName == normalizedPackageName }
    val replacedByNewPending =
        normalizedPackageName in current.expert.pendingQuarantinePackages &&
            existingDetails?.detectedAt != detectedAt
    val shouldUpdateAudit =
        current.shouldUpdateEnrichedAudit(
            inventoryContainsPackage = inventoryContainsPackage,
            installedIdentity = installedIdentity,
            sameInstallation = sameInstallation,
            replacedByNewPending = replacedByNewPending,
        )
    val audit =
        if (shouldUpdateAudit) {
            current.installedAppInventoryAudit.withInstalledAppChange(
                entry =
                InstalledAppInventoryEntry(
                    packageName = normalizedPackageName,
                    label = enrichedDetails.label,
                    isSystemApp = isSystemApp,
                    installerPackageName = enrichedDetails.installerPackageName,
                    riskLevel = riskLevel,
                    riskSignals = enrichedDetails.riskSignals,
                ),
                type = InstalledAppChangeType.INSTALLED,
                detectedAt = detectedAt,
            )
        } else {
            current.installedAppInventoryAudit
        }
    return InstalledAppChangeEnrichment(
        settings = current.copy(expert = expert, installedAppInventoryAudit = audit),
        outcome = outcome,
    )
}

private fun PendingQuarantineAppDetails?.matchesPendingEnrichment(
    packageName: String,
    detectedAt: Long,
    pendingPackages: List<String>,
    sameInstallation: Boolean,
): Boolean =
    packageName in pendingPackages &&
        this?.detectedAt == detectedAt &&
        !analysisComplete &&
        sameInstallation

private fun ExpertSettings.afterInstalledAppEnrichment(
    packageName: String,
    pendingBefore: Boolean,
    isSystemApp: Boolean,
    installedIdentity: KnownApplicationIdentity?,
    enrichedDetails: PendingQuarantineAppDetails,
): ExpertSettings =
    when {
        !pendingBefore -> this
        isSystemApp && installedIdentity != null ->
            removeAppPackage(packageName)
                .withKnownQuarantineIdentity(installedIdentity)
        isSystemApp -> this
        else ->
            copy(
                pendingQuarantineAppDetails =
                pendingQuarantineAppDetails.filterNot { details -> details.packageName == packageName } +
                    enrichedDetails,
            )
    }

private fun Settings.shouldUpdateEnrichedAudit(
    inventoryContainsPackage: Boolean,
    installedIdentity: KnownApplicationIdentity?,
    sameInstallation: Boolean,
    replacedByNewPending: Boolean,
): Boolean {
    val auditEnabled = statistics.enabled && statistics.appChangesEnabled
    val exactInstalledPackage = inventoryContainsPackage && installedIdentity != null
    val matchingInstallation = exactInstalledPackage && sameInstallation
    return auditEnabled && matchingInstallation && !replacedByNewPending
}

internal suspend fun SettingsRepository.incompleteQuarantineAnalyses(
    limit: Int = MAX_PENDING_QUARANTINE_ANALYSIS_BATCH,
): List<PendingQuarantineAppDetails> {
    val expert = current().expert
    return expert.pendingQuarantineAppDetails
        .asSequence()
        .filterNot(PendingQuarantineAppDetails::analysisComplete)
        .filter { details -> details.packageName in expert.pendingQuarantinePackages }
        .take(limit.coerceIn(1, MAX_PENDING_QUARANTINE_ANALYSIS_BATCH))
        .toList()
}

/** Removes only the exact unfinished install observed by a retry; a reinstall gets a new timestamp. */
internal suspend fun SettingsRepository.removeMissingIncompleteQuarantine(
    packageName: String,
    expectedDetectedAt: Long,
): Boolean {
    var removed = false
    update { current ->
        val details =
            current.expert.pendingQuarantineAppDetails.firstOrNull { candidate ->
                candidate.packageName == packageName &&
                    candidate.detectedAt == expectedDetectedAt &&
                    !candidate.analysisComplete
            }
        if (details == null || packageName !in current.expert.pendingQuarantinePackages) {
            current
        } else {
            removed = true
            current.copy(expert = current.expert.removeAppPackage(packageName))
        }
    }
    return removed
}

/**
 * Closes the enable-time snapshot race: identities absent from the frozen baseline become pending
 * decisions. Assigned packages are already an explicit user decision and are left untouched.
 */
internal suspend fun SettingsRepository.reconcileNewAppQuarantineGaps(
    detectedAt: Long = System.currentTimeMillis(),
): Int {
    val liveApplications = captureKnownApplicationsForQuarantine()
    var added = 0
    update { current ->
        val result = quarantineBaselineGapsIn(current, liveApplications, detectedAt)
        added = result.expert.pendingQuarantinePackages.size - current.expert.pendingQuarantinePackages.size
        result
    }
    return added.coerceAtLeast(0)
}

internal fun quarantineBaselineGapsIn(
    current: Settings,
    liveApplications: List<KnownApplicationIdentity>,
    detectedAt: Long,
): Settings {
    if (!current.expert.newAppQuarantineEnabled) return current
    val known = current.expert.quarantineKnownApplications.mapTo(mutableSetOf(), KnownApplicationIdentity::packageName)
    val decidedOrPending =
        buildSet {
            addAll(current.expert.appAssignments.keys)
            addAll(current.expert.pendingQuarantinePackages)
            add(BuildConfig.APPLICATION_ID)
        }
    return liveApplications
        .asSequence()
        .filterNot { identity -> identity.packageName in known || identity.packageName in decidedOrPending }
        .distinctBy(KnownApplicationIdentity::packageName)
        .fold(current) { settings, identity ->
            recordInstalledAppChangeIn(
                current = settings,
                packageName = identity.packageName,
                label = identity.packageName,
                isSystemApp = false,
                type = InstalledAppChangeType.INSTALLED,
                detectedAt = detectedAt,
                firstInstallTime = identity.firstSeenAtMs,
                quarantineAnalysisComplete = false,
            )
        }
}

private const val MAX_PENDING_QUARANTINE_ANALYSIS_BATCH = 8

/**
 * The lane/quarantine half of an install or removal: what the change does to the expert block.
 * Extracted from [recordInstalledAppChange] unchanged — the branches stay in the same order, so
 * quarantining a fresh install still wins over the known-identity refresh.
 */
private fun ExpertSettings.afterInstalledAppChange(
    packageName: String,
    type: InstalledAppChangeType,
    isSystemApp: Boolean,
    quarantineNewApp: Boolean,
    quarantineIdentity: KnownApplicationIdentity?,
    quarantineDetails: PendingQuarantineAppDetails?,
): ExpertSettings =
    when {
        quarantineNewApp -> quarantinePackage(packageName, quarantineDetails)
        type == InstalledAppChangeType.REMOVED -> removeAppPackage(packageName)
        type == InstalledAppChangeType.INSTALLED && isSystemApp && quarantineIdentity != null ->
            removeAppPackage(packageName).withKnownQuarantineIdentity(quarantineIdentity)
        else -> this
    }

/** The inventory half of an install or removal: the package list plus one dated history entry. */
private fun InstalledAppInventoryAudit.withInstalledAppChange(
    entry: InstalledAppInventoryEntry,
    type: InstalledAppChangeType,
    detectedAt: Long,
): InstalledAppInventoryAudit {
    val updatedPackages =
        when (type) {
            InstalledAppChangeType.INSTALLED ->
                (packages.filterNot { app -> app.packageName == entry.packageName } + entry)
                    .sortedBy(InstalledAppInventoryEntry::packageName)
            InstalledAppChangeType.REMOVED ->
                packages.filterNot { app -> app.packageName == entry.packageName }
        }
    val change =
        InstalledAppInventoryChange(
            packageName = entry.packageName,
            label = entry.label,
            isSystemApp = entry.isSystemApp,
            type = type,
            detectedAt = detectedAt,
            installerPackageName = entry.installerPackageName,
            riskLevel = entry.riskLevel,
            riskSignals = entry.riskSignals,
        )
    return copy(
        capturedAt = detectedAt,
        packages = updatedPackages,
        recentChanges =
        (listOf(change) + recentChanges)
            .distinctBy { item -> "${item.type}:${item.packageName}:${item.detectedAt}" }
            .sortedByDescending(InstalledAppInventoryChange::detectedAt)
            .take(INSTALLED_APP_CHANGE_HISTORY_LIMIT),
    )
}

/** Applies package changes recovered by the tamper-evident inventory reconciliation. */
suspend fun SettingsRepository.reconcileQuarantineFromGuardEvents(events: List<GuardEvent>) {
    events.forEach { event ->
        val packageName = event.packageName?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
        when (event.type) {
            GuardEventType.PACKAGE_REMOVED ->
                recordInstalledAppChange(
                    packageName = packageName,
                    label = packageName,
                    isSystemApp = false,
                    type = InstalledAppChangeType.REMOVED,
                )
            GuardEventType.PACKAGE_ADDED -> reconcileUnexpectedInstalledPackage(packageName)
            GuardEventType.PACKAGE_REPLACED -> reconcileReplacedPackageIdentity(packageName)
            else -> Unit
        }
    }
}

private suspend fun SettingsRepository.reconcileReplacedPackageIdentity(packageName: String) {
    val settings = current()
    if (!settings.expert.newAppQuarantineEnabled) return
    val currentIdentity = currentQuarantineIdentity(packageName) ?: return
    val known =
        settings.expert.quarantineKnownApplications
            .firstOrNull { application -> application.packageName == packageName }
    val identityChanged =
        known == null ||
            (
                known.signingCertificateSha256 != null &&
                    known.signingCertificateSha256 != currentIdentity.signingCertificateSha256
                ) ||
            (known.firstSeenAtMs != null && known.firstSeenAtMs != currentIdentity.firstSeenAtMs)
    if (identityChanged) {
        reconcileUnexpectedInstalledPackage(packageName, currentIdentity)
    } else {
        update { current ->
            current.copy(expert = current.expert.withKnownQuarantineIdentity(currentIdentity))
        }
    }
}

private suspend fun SettingsRepository.reconcileUnexpectedInstalledPackage(
    packageName: String,
    resolvedIdentity: KnownApplicationIdentity? = null,
) {
    val settings = current()
    if (!settings.expert.newAppQuarantineEnabled) return
    val security =
        withContext(Dispatchers.IO) {
            installedAppSecurityAnalyzer.analyzePackage(
                packageName = packageName,
                fallbackLabel = packageName,
                fallbackIsSystemApp = false,
            )
        }
    val identity =
        if (security.isSystemApp) {
            resolvedIdentity ?: currentQuarantineIdentity(packageName)
        } else {
            null
        }
    recordInstalledAppChange(
        packageName = packageName,
        label = security.label,
        isSystemApp = security.isSystemApp,
        type = InstalledAppChangeType.INSTALLED,
        installerPackageName = security.installerPackageName,
        riskLevel = security.riskLevel,
        riskSignals = security.riskSignals,
        quarantineIdentity = identity,
    )
}
