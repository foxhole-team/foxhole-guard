package com.foxhole.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.foxhole.core.model.InstalledAppChangeType
import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppRiskSignal
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.Settings
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.packages
import com.foxhole.guard.core.sentinel.InstalledAppSecurityAnalyzer
import com.foxhole.guard.core.sentinel.InstalledAppSecurityNotifier
import com.foxhole.guard.core.sentinel.InstalledAppSecuritySummary
import com.foxhole.guard.core.sentinel.SentinelThreatIntelProvider
import com.foxhole.guard.core.settings.enrichInstalledAppChange
import com.foxhole.guard.core.settings.incompleteQuarantineAnalyses
import com.foxhole.guard.core.settings.recordInstalledAppChange
import com.foxhole.guard.core.settings.removeMissingIncompleteQuarantine
import com.foxhole.guard.guardian.GuardEventType
import com.foxhole.guard.guardian.enqueuePendingQuarantineAnalysis
import com.foxhole.guard.runtime.QuarantineEnforcementOutcome
import com.foxhole.guard.runtime.enqueueQuarantineRuntimeEnforcement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class InstalledAppChangeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext as? FoxholeApplication ?: return
        val guardChange = intent.guardPackageChangeOrNull()
        val packageChange =
            if (intent.action == Intent.ACTION_PACKAGE_ADDED || intent.action == Intent.ACTION_PACKAGE_REMOVED) {
                intent.packageInventoryChangeOrNull()
            } else {
                null
            }
        if (packageChange == null && guardChange == null) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            finishPendingBroadcast(
                timeoutMs = PACKAGE_CHANGE_TIMEOUT_MS,
                finish = pendingResult::finish,
                onTimeout = {
                    app.container.diagnosticsLogger.record(
                        "app-inventory",
                        "package change receiver timed out",
                    )
                },
            ) {
                val preparedChange =
                    when (val preparation = packageChange?.let { app.preparePackageInventoryChange(it) }) {
                        is PackageChangePreparation.Failed -> {
                            app.container.diagnosticsLogger.record(
                                "app-inventory",
                                "package change preflight failed: ${preparation.error.javaClass.simpleName}",
                            )
                            // The inventory snapshot must not advance past an install whose durable
                            // quarantine decision could not be written. Reconciliation will retry it.
                            return@finishPendingBroadcast
                        }
                        is PackageChangePreparation.Persisted -> preparation.change
                        PackageChangePreparation.NotRequired,
                        null,
                        -> null
                    }
                if (preparedChange?.preflight?.quarantinedInstall == true) {
                    app.enqueuePendingQuarantineAnalysis()
                }
                // Guard journal is independent of the statistics toggles. It runs after the
                // durable quarantine preflight, but before optional FoxHole Sentinel enrichment.
                if (guardChange != null && app.appGraph.securityComponents.isEventMonitoringActive()) {
                    runCatching {
                        app.appGraph.securityComponents.guardSentinel.recordPackageChange(
                            guardChange.second,
                            guardChange.first,
                        )
                    }
                }
                if (preparedChange != null) {
                    app.enrichPackageInventoryChange(preparedChange)
                }
            }
        }
    }

    private companion object {
        const val PACKAGE_CHANGE_TIMEOUT_MS = 8_000L
    }
}

/**
 * What one install/removal is worth to this app: the consent gates it passes and whether the live
 * runtime has to hear about it at all.
 */
private data class PackageChangeRelevance(
    val monitoringEnabled: Boolean,
    val quarantineEnabled: Boolean,
    val laneAssignedChange: Boolean,
    val quarantineRelevantChange: Boolean,
) {
    /** Nothing to record and nothing to re-apply: the change may be dropped. */
    val ignorable: Boolean
        get() = !monitoringEnabled && !quarantineRelevantChange && !laneAssignedChange

    /** The change may touch the inventory audit and the security analysis. */
    val auditable: Boolean
        get() = monitoringEnabled || quarantineRelevantChange

    val runtimeRelevant: Boolean
        get() = laneAssignedChange
}

/** Basic PackageManager facts captured before optional FoxHole Sentinel enrichment. */
private data class PackageChangePreflight(
    val packageInfo: PackageInventoryInfo,
    val recordedAt: Long,
    val quarantinedInstall: Boolean,
)

private data class PreparedPackageChange(
    val change: PackageInventoryChange,
    val relevance: PackageChangeRelevance,
    val preflight: PackageChangePreflight,
)

private sealed interface PackageChangePreparation {
    data object NotRequired : PackageChangePreparation

    data class Persisted(val change: PreparedPackageChange) : PackageChangePreparation

    data class Failed(val error: Throwable) : PackageChangePreparation
}

private fun Settings.packageChangeRelevance(change: PackageInventoryChange): PackageChangeRelevance {
    val quarantineEnabled = expert.newAppQuarantineEnabled
    return PackageChangeRelevance(
        monitoringEnabled = anomaly.enabled && statistics.enabled && statistics.appChangesEnabled,
        quarantineEnabled = quarantineEnabled,
        // Any lane membership (tor/vpn/block/exclude) makes the change runtime-relevant:
        // an uninstall must drop the package's UID from the live tun filter, and a fresh
        // reinstall arrives with a NEW UID the running filter knows nothing about — both
        // used to wait for a manual reconnect unless quarantine happened to be on.
        laneAssignedChange = change.packageName in expert.appAssignments,
        quarantineRelevantChange =
        quarantineEnabled &&
            (
                change.type == InstalledAppChangeType.INSTALLED ||
                    change.packageName in expert.blockedLanePackages()
                ),
    )
}

private suspend fun FoxholeApplication.preparePackageInventoryChange(
    change: PackageInventoryChange,
): PackageChangePreparation =
    try {
        val relevance = container.settingsRepository.current().packageChangeRelevance(change)
        if (relevance.ignorable) {
            PackageChangePreparation.NotRequired
        } else {
            PackageChangePreparation.Persisted(
                PreparedPackageChange(
                    change = change,
                    relevance = relevance,
                    preflight = preflightPackageChange(change, relevance),
                ),
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        PackageChangePreparation.Failed(error)
    }

/**
 * Persists and applies the minimum BLOCK rule before threat-intel or component analysis begins.
 * If enrichment later fails or the broadcast deadline expires, the pending decision survives and
 * the package remains fail-closed.
 */
private suspend fun FoxholeApplication.preflightPackageChange(
    change: PackageInventoryChange,
    relevance: PackageChangeRelevance,
): PackageChangePreflight {
    val packageInfo = packageInventoryInfo(change.packageName, change.type)
    val recordedAt = System.currentTimeMillis()
    val quarantinedInstall =
        change.type == InstalledAppChangeType.INSTALLED &&
            relevance.quarantineEnabled &&
            !packageInfo.isSystemApp
    val quarantineIdentity =
        quarantineIdentityFor(change, packageInfo.isSystemApp, relevance).also { identity ->
            if (
                change.type == InstalledAppChangeType.INSTALLED &&
                relevance.quarantineEnabled &&
                packageInfo.isSystemApp
            ) {
                requireNotNull(identity) { "system package identity unavailable during quarantine preflight" }
            }
        }
    // Persist every relevant package mutation before the sealed inventory snapshot can move. For
    // a quarantined install this also creates the durable BLOCK rule with an explicit pending-risk
    // marker; enrichment later updates facts only and is never allowed to recreate a decision.
    container.settingsRepository.recordInstalledAppChange(
        packageName = change.packageName,
        label = packageInfo.label,
        isSystemApp = packageInfo.isSystemApp,
        type = change.type,
        detectedAt = recordedAt,
        firstInstallTime = packageInfo.firstInstallTime,
        installerPackageName = packageInfo.installerPackageName,
        riskLevel = packageInfo.riskLevel,
        riskSignals = packageInfo.riskSignals,
        quarantineIdentity = quarantineIdentity,
        quarantineAnalysisComplete = !quarantinedInstall,
    )
    if (quarantinedInstall || quarantineIdentity != null || relevance.runtimeRelevant) {
        runCatching { applyQuarantineToActiveRuntime() }
            .onFailure { error ->
                container.diagnosticsLogger.record(
                    "app-inventory",
                    "package preflight runtime apply failed: ${error.javaClass.simpleName}",
                )
            }
    }
    return PackageChangePreflight(
        packageInfo = packageInfo,
        recordedAt = recordedAt,
        quarantinedInstall = quarantinedInstall,
    )
}

private suspend fun FoxholeApplication.enrichPackageInventoryChange(prepared: PreparedPackageChange) {
    val change = prepared.change
    val relevance = prepared.relevance
    val preflight = prepared.preflight
    if (!relevance.auditable || change.type != InstalledAppChangeType.INSTALLED) return
    val packageInfo = preflight.packageInfo
    val securitySummary = analyzeInstalledPackage(change, packageInfo, relevance) ?: return
    val resolvedLabel = securitySummary.label
    val resolvedIsSystemApp = securitySummary.isSystemApp
    val quarantineIdentity = quarantineIdentityFor(change, resolvedIsSystemApp, relevance)
    val installedIdentity = container.settingsRepository.currentQuarantineIdentity(change.packageName) ?: return
    val outcome = container.settingsRepository.enrichInstalledAppChange(
        packageName = change.packageName,
        detectedAt = preflight.recordedAt,
        firstInstallTime = packageInfo.firstInstallTime,
        label = resolvedLabel,
        isSystemApp = resolvedIsSystemApp,
        installerPackageName = securitySummary.installerPackageName ?: packageInfo.installerPackageName,
        riskLevel = securitySummary.riskLevel,
        riskSignals = securitySummary.riskSignals,
        installedIdentity = quarantineIdentity ?: installedIdentity,
    )
    if (outcome.routeChanged) {
        runCatching { applyQuarantineToActiveRuntime() }
            .onFailure { error ->
                container.diagnosticsLogger.record(
                    "app-inventory",
                    "package enrichment runtime apply failed: ${error.javaClass.simpleName}",
                )
            }
    }
    if (outcome.analysisCompletedNow && (relevance.monitoringEnabled || outcome.pendingAfterCommit)) {
        InstalledAppSecurityNotifier(this).notifyInstalledApp(
            summary = securitySummary,
            quarantined = outcome.pendingAfterCommit,
        )
    }
}

private fun FoxholeApplication.analyzeInstalledPackage(
    change: PackageInventoryChange,
    packageInfo: PackageInventoryInfo,
    relevance: PackageChangeRelevance,
): InstalledAppSecuritySummary? =
    if (
        change.type == InstalledAppChangeType.INSTALLED &&
        (relevance.monitoringEnabled || relevance.quarantineEnabled)
    ) {
        InstalledAppSecurityAnalyzer(
            this,
            SentinelThreatIntelProvider(this)::current,
        ).analyzePackageResult(
            packageName = change.packageName,
            fallbackLabel = packageInfo.label,
        ).onFailure { error ->
            container.diagnosticsLogger.record(
                "app-inventory",
                "security analysis deferred " +
                    "package_hash=${change.packageName.stablePackageHash()} " +
                    "error=${error.javaClass.simpleName}",
            )
        }.getOrNull()
    } else {
        null
    }

/** A system app installed under quarantine extends the fail-closed baseline instead of being blocked. */
private suspend fun FoxholeApplication.quarantineIdentityFor(
    change: PackageInventoryChange,
    resolvedIsSystemApp: Boolean,
    relevance: PackageChangeRelevance,
): KnownApplicationIdentity? =
    if (
        change.type == InstalledAppChangeType.INSTALLED &&
        resolvedIsSystemApp &&
        relevance.quarantineEnabled
    ) {
        container.settingsRepository.currentQuarantineIdentity(change.packageName)
    } else {
        null
    }

// Persistence schedules a durable retry; this inline pass minimizes the install-to-BLOCK window.
// An accepted service intent is not success: the controller waits for the exact session receipt.
internal suspend fun FoxholeApplication.applyQuarantineToActiveRuntime() {
    val controller = container.connectionController
    val revision = container.settingsRepository.current().expert.quarantinePolicyRevision
    enqueueQuarantineRuntimeEnforcement(revision)
    when (controller.enforceQuarantinePolicy(revision)) {
        QuarantineEnforcementOutcome.APPLIED ->
            container.diagnosticsLogger.record("app-inventory", "quarantine applied to active runtime")
        QuarantineEnforcementOutcome.GENUINELY_IDLE ->
            container.diagnosticsLogger.record("app-inventory", "quarantine persisted while runtime idle")
        QuarantineEnforcementOutcome.RETRY ->
            container.diagnosticsLogger.record("app-inventory", "quarantine enforcement deferred to durable retry")
    }
}

private fun String.stablePackageHash(): Int =
    trim()
        .takeIf(String::isNotBlank)
        ?.hashCode()
        ?: 0

/**
 * Completes bounded, persisted analysis work after a receiver timeout or process death. Returns
 * true while another WorkManager pass is needed.
 */
internal suspend fun FoxholeApplication.reconcilePendingQuarantineAnalyses(): Boolean {
    val repository = container.settingsRepository
    val candidates = repository.incompleteQuarantineAnalyses()
    if (candidates.isEmpty()) return false
    val analyzer = InstalledAppSecurityAnalyzer(this, SentinelThreatIntelProvider(this)::current)
    candidates.forEach { candidate ->
        val identityResult = runCatching { repository.currentQuarantineIdentity(candidate.packageName) }
        if (identityResult.isFailure) {
            container.diagnosticsLogger.record(
                "app-inventory",
                "pending quarantine identity retry failed " +
                    "package_hash=${candidate.packageName.stablePackageHash()}",
            )
            return@forEach
        }
        val identity = identityResult.getOrNull()
        if (identity == null) {
            if (repository.removeMissingIncompleteQuarantine(candidate.packageName, candidate.detectedAt)) {
                runCatching { applyQuarantineToActiveRuntime() }
            }
            return@forEach
        }
        val summary =
            analyzer.analyzePackageResult(candidate.packageName, candidate.label)
                .onFailure { error ->
                    container.diagnosticsLogger.record(
                        "app-inventory",
                        "pending quarantine analysis deferred " +
                            "package_hash=${candidate.packageName.stablePackageHash()} " +
                            "error=${error.javaClass.simpleName}",
                    )
                }.getOrNull()
                ?: return@forEach
        val outcome =
            repository.enrichInstalledAppChange(
                packageName = candidate.packageName,
                detectedAt = candidate.detectedAt,
                firstInstallTime = candidate.firstInstallTime,
                label = summary.label,
                isSystemApp = summary.isSystemApp,
                installerPackageName = summary.installerPackageName,
                riskLevel = summary.riskLevel,
                riskSignals = summary.riskSignals,
                installedIdentity = identity,
            )
        if (outcome.routeChanged) {
            runCatching { applyQuarantineToActiveRuntime() }
        }
        if (outcome.analysisCompletedNow && outcome.pendingAfterCommit) {
            InstalledAppSecurityNotifier(this).notifyInstalledApp(summary = summary, quarantined = true)
        }
    }
    return repository.incompleteQuarantineAnalyses(limit = 1).isNotEmpty()
}

private data class PackageInventoryChange(
    val packageName: String,
    val type: InstalledAppChangeType,
)

private fun Intent.guardPackageChangeOrNull(): Pair<String, GuardEventType>? {
    val packageName = data?.schemeSpecificPart?.takeIf(String::isNotBlank) ?: return null
    val replacing = getBooleanExtra(Intent.EXTRA_REPLACING, false)
    val type =
        when (action) {
            Intent.ACTION_PACKAGE_REPLACED -> GuardEventType.PACKAGE_REPLACED
            Intent.ACTION_PACKAGE_ADDED -> if (replacing) GuardEventType.PACKAGE_REPLACED else GuardEventType.PACKAGE_ADDED
            Intent.ACTION_PACKAGE_REMOVED -> if (replacing) return null else GuardEventType.PACKAGE_REMOVED
            else -> return null
        }
    return packageName to type
}

private fun Intent.packageInventoryChangeOrNull(): PackageInventoryChange? {
    val packageName = data?.schemeSpecificPart?.takeIf(String::isNotBlank)
    val changeType =
        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> InstalledAppChangeType.INSTALLED
            Intent.ACTION_PACKAGE_REMOVED -> InstalledAppChangeType.REMOVED
            else -> null
        }
    return changeType
        ?.takeUnless { getBooleanExtra(Intent.EXTRA_REPLACING, false) }
        ?.let { type -> packageName?.let { name -> PackageInventoryChange(name, type) } }
}

internal suspend fun finishPendingBroadcast(
    timeoutMs: Long,
    finish: () -> Unit,
    onTimeout: () -> Unit = {},
    block: suspend () -> Unit,
) {
    try {
        withTimeout(timeoutMs) {
            block()
        }
    } catch (_: TimeoutCancellationException) {
        onTimeout()
    } finally {
        finish()
    }
}

private data class PackageInventoryInfo(
    val label: String,
    val isSystemApp: Boolean,
    val firstInstallTime: Long? = null,
    val installerPackageName: String? = null,
    val riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
    val riskSignals: List<InstalledAppRiskSignal> = emptyList(),
)

private fun Context.packageInventoryInfo(
    packageName: String,
    changeType: InstalledAppChangeType,
): PackageInventoryInfo {
    val packageManager = packageManager
    val appInfo =
        if (changeType == InstalledAppChangeType.INSTALLED) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0)
                }
            }.getOrNull()
        } else {
            null
        }
    val installedPackageInfo =
        if (changeType == InstalledAppChangeType.INSTALLED) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }
            }.getOrNull()
        } else {
            null
        }
    val previous =
        (applicationContext as? FoxholeApplication)
            ?.container
            ?.settingsRepository
            ?.settings
            ?.value
            ?.installedAppInventoryAudit
            ?.packages
            ?.firstOrNull { app -> app.packageName == packageName }
    return PackageInventoryInfo(
        label = appInfo?.loadLabel(packageManager)?.toString()?.takeIf(String::isNotBlank)
            ?: previous?.label
            ?: packageName,
        // A fresh install whose PackageManager row is momentarily unavailable is unknown, not a
        // trusted system app. Fail closed now; complete analysis can promote a real system app.
        isSystemApp = appInfo?.isSystemApp() ?: false,
        firstInstallTime = installedPackageInfo?.firstInstallTime?.takeIf { value -> value > 0L },
        installerPackageName = previous?.installerPackageName,
        riskLevel = previous?.riskLevel ?: InstalledAppRiskLevel.LOW,
        riskSignals = previous?.riskSignals.orEmpty(),
    )
}

private fun ApplicationInfo.isSystemApp(): Boolean =
    flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
        flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
