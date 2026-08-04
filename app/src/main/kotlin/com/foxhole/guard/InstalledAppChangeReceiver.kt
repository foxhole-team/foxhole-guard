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
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.packages
import com.foxhole.core.runtime.isActiveProfileRuntime
import com.foxhole.guard.core.sentinel.InstalledAppSecurityAnalyzer
import com.foxhole.guard.core.sentinel.InstalledAppSecurityNotifier
import com.foxhole.guard.core.sentinel.SentinelThreatIntelProvider
import com.foxhole.guard.core.settings.recordInstalledAppChange
import com.foxhole.guard.guardian.GuardEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class InstalledAppChangeReceiver : BroadcastReceiver() {
    @Suppress("CyclomaticComplexMethod")
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
                // Guard journal is independent of the statistics toggles: while a custom password
                // is on, every install/removal/replacement is sealed into the tamper-evident log.
                if (guardChange != null && app.appGraph.securityComponents.isEventMonitoringActive()) {
                    runCatching {
                        app.appGraph.securityComponents.guardSentinel.recordPackageChange(
                            guardChange.second,
                            guardChange.first,
                        )
                    }
                }
                if (packageChange == null) {
                    return@finishPendingBroadcast
                }
                runCatching {
                    val settings = app.container.settingsRepository.current()
                    val monitoringEnabled = settings.statistics.enabled && settings.statistics.appChangesEnabled
                    val quarantineEnabled = settings.expert.newAppQuarantineEnabled
                    // Any lane membership (tor/vpn/block/exclude) makes the change runtime-relevant:
                    // an uninstall must drop the package's UID from the live tun filter, and a fresh
                    // reinstall arrives with a NEW UID the running filter knows nothing about — both
                    // used to wait for a manual reconnect unless quarantine happened to be on.
                    val laneAssignedChange = packageChange.packageName in settings.expert.appAssignments
                    val quarantineRelevantChange =
                        quarantineEnabled &&
                            (
                                packageChange.type == InstalledAppChangeType.INSTALLED ||
                                    packageChange.packageName in settings.expert.blockedLanePackages()
                                )
                    if (!monitoringEnabled && !quarantineRelevantChange && !laneAssignedChange) {
                        return@runCatching
                    }
                    // Inventory recording and security analysis stay behind their own consent gates;
                    // a lane-only change must not grow the audit while monitoring is off.
                    var quarantinedInstall = false
                    if (monitoringEnabled || quarantineRelevantChange) {
                        val packageInfo = app.packageInventoryInfo(packageChange.packageName, packageChange.type)
                        val securitySummary =
                            if (
                                packageChange.type == InstalledAppChangeType.INSTALLED &&
                                (monitoringEnabled || quarantineEnabled)
                            ) {
                                InstalledAppSecurityAnalyzer(
                                    app,
                                    SentinelThreatIntelProvider(app)::current,
                                    onAnalysisFallback = { fallbackPackage, error ->
                                        app.container.diagnosticsLogger.record(
                                            "app-inventory",
                                            "security analysis fell back to blank facts " +
                                                "package_hash=${fallbackPackage.stablePackageHash()} " +
                                                "error=${error.javaClass.simpleName}",
                                        )
                                    },
                                ).analyzePackage(
                                    packageName = packageChange.packageName,
                                    fallbackLabel = packageInfo.label,
                                    fallbackIsSystemApp = packageInfo.isSystemApp,
                                )
                            } else {
                                null
                            }
                        val resolvedLabel = securitySummary?.label ?: packageInfo.label
                        val resolvedIsSystemApp = securitySummary?.isSystemApp ?: packageInfo.isSystemApp
                        app.container.settingsRepository.recordInstalledAppChange(
                            packageName = packageChange.packageName,
                            label = resolvedLabel,
                            isSystemApp = resolvedIsSystemApp,
                            type = packageChange.type,
                            installerPackageName = securitySummary?.installerPackageName ?: packageInfo.installerPackageName,
                            riskLevel = securitySummary?.riskLevel ?: packageInfo.riskLevel,
                            riskSignals = securitySummary?.riskSignals ?: packageInfo.riskSignals,
                        )
                        quarantinedInstall =
                            packageChange.type == InstalledAppChangeType.INSTALLED &&
                            !resolvedIsSystemApp && quarantineEnabled
                        if (securitySummary != null && (monitoringEnabled || quarantinedInstall)) {
                            InstalledAppSecurityNotifier(app).notifyInstalledApp(
                                summary = securitySummary,
                                quarantined = quarantinedInstall,
                            )
                        }
                    }
                    if (quarantinedInstall || laneAssignedChange) {
                        app.applyQuarantineToActiveRuntime()
                    }
                }.onFailure { error ->
                    app.container.diagnosticsLogger.record(
                        "app-inventory",
                        "package change record failed: ${error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private companion object {
        const val PACKAGE_CHANGE_TIMEOUT_MS = 8_000L
    }
}

// A freshly quarantined package must take effect on whatever runtime is live. syncLocalGuard()
// alone defers while a profile tunnel/tor session owns the runtime (it yields to the active
// profile), so the new block only applied after a manual reconnect — the app kept full network in
// the meantime. Hot-reload the active profile runtime to re-assemble its route rules immediately;
// with no profile runtime up, fall back to the firewall-guard sync as before.
private suspend fun FoxholeApplication.applyQuarantineToActiveRuntime() {
    val controller = container.connectionController
    val snapshot = controller.snapshot.value
    val profileId = snapshot.profileId
    if (snapshot.isActiveProfileRuntime() && profileId != null && controller.reload(profileId)) {
        container.diagnosticsLogger.record("app-inventory", "quarantine hot-reloaded the active runtime")
        return
    }
    controller.syncLocalGuard()
}

private fun String.stablePackageHash(): Int =
    trim()
        .takeIf(String::isNotBlank)
        ?.hashCode()
        ?: 0

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
        isSystemApp = appInfo?.isSystemApp() ?: previous?.isSystemApp ?: false,
        installerPackageName = previous?.installerPackageName,
        riskLevel = previous?.riskLevel ?: InstalledAppRiskLevel.LOW,
        riskSignals = previous?.riskSignals.orEmpty(),
    )
}

private fun ApplicationInfo.isSystemApp(): Boolean =
    flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
        flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
