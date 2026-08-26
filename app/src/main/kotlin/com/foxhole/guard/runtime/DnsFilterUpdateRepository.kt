package com.foxhole.guard.runtime

import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.core.model.requestedDnsRuleSetTags
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.core.runtime.RuntimeDnsRuleSetInstallOutcome
import com.foxhole.core.runtime.RuntimeSettings

class DnsFilterUpdateRepository(
    private val settingsRepository: RuntimeSettings,
    private val client: DnsFilterUpdateClient,
    private val store: DnsFilterRuleSetStore,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val installedManifestProvider: suspend () -> DnsFilterManifest? = { null },
    private val installedRuleSetsProvider: suspend () -> InstalledDnsRuleSets = { InstalledDnsRuleSets() },
    private val liveRuleSetInstaller: suspend (
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
    ) -> RuntimeDnsRuleSetInstallOutcome = { _, _, _, _ ->
        RuntimeDnsRuleSetInstallOutcome.Deferred
    },
) {
    suspend fun checkForUpdate(dnsSettingsOverride: DnsSettings? = null): DnsFilterUpdateCheck {
        val dnsSettings = dnsSettingsOverride ?: settingsRepository.current().dns
        val installedCommit = installedManifestProvider()?.source?.commit
        val check =
            client.checkForUpdate(
                manifestUrl = dnsSettings.dnsFilterUpdateUrl,
                installedCommit = installedCommit,
                requestedTags = dnsSettings.requestedDnsRuleSetTags(),
                installedRuleSets = installedRuleSetsProvider(),
            )
        diagnosticsLogger.record(
            "dns",
            "filter update check availability=${check.availability.name.lowercase()} reason=${check.reason.orEmpty()}",
        )
        if (check.availability != DnsFilterUpdateAvailability.UNKNOWN) {
            settingsRepository.markDnsFiltersChecked()
        }
        return check
    }

    suspend fun autoRefresh(
        forcedRefreshIntervalMs: Long,
        nowMs: () -> Long = System::currentTimeMillis,
    ): DnsFilterUpdateResult {
        val dnsSettings = settingsRepository.current().dns
        val skipReason =
            when {
                !dnsSettings.dnsRuleSetFilteringEnabled() -> "filtering disabled"
                !dnsSettings.autoUpdateFilters -> "automatic updates disabled"
                else -> null
            }
        if (skipReason != null) {
            return DnsFilterUpdateResult(status = DnsFilterUpdateStatus.SKIPPED, reason = skipReason)
        }
        val updatedAt = dnsSettings.filtersUpdatedAt
        val forcedRefreshDue = updatedAt == null || nowMs() - updatedAt >= forcedRefreshIntervalMs
        if (!forcedRefreshDue) {
            val check = checkForUpdate(dnsSettingsOverride = dnsSettings)
            when (check.availability) {
                DnsFilterUpdateAvailability.UP_TO_DATE ->
                    return DnsFilterUpdateResult(
                        status = DnsFilterUpdateStatus.UP_TO_DATE,
                        sourceCommit = check.remoteCommit,
                    )
                DnsFilterUpdateAvailability.UNKNOWN ->
                    return DnsFilterUpdateResult(
                        status = DnsFilterUpdateStatus.FAILED,
                        retryable = true,
                        reason = check.reason,
                    )
                DnsFilterUpdateAvailability.UPDATE_AVAILABLE -> Unit
            }
        }
        return refreshNow(requireAutoEnabled = true, dnsSettingsOverride = dnsSettings)
    }

    suspend fun refreshNow(
        requireAutoEnabled: Boolean,
        dnsSettingsOverride: DnsSettings? = null,
        onPhase: (RemoteUpdatePhase) -> Unit = {},
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): DnsFilterUpdateResult {
        val dnsSettings = dnsSettingsOverride ?: settingsRepository.current().dns
        var liveActivation: RuntimeDnsRuleSetInstallOutcome? = null
        val activatingStore =
            LiveActivatingDnsRuleSetStore(
                delegate = store,
                installer = liveRuleSetInstaller,
                onOutcome = { outcome -> liveActivation = outcome },
            )
        val result =
            when {
                !dnsSettings.dnsRuleSetFilteringEnabled() ->
                    DnsFilterUpdateResult(status = DnsFilterUpdateStatus.SKIPPED, reason = "filtering disabled")
                requireAutoEnabled && !dnsSettings.autoUpdateFilters ->
                    DnsFilterUpdateResult(status = DnsFilterUpdateStatus.SKIPPED, reason = "automatic updates disabled")
                else ->
                    client.update(
                        manifestUrl = dnsSettings.dnsFilterUpdateUrl,
                        store = activatingStore,
                        requestedTags = dnsSettings.requestedDnsRuleSetTags(),
                        installedRuleSets = installedRuleSetsProvider(),
                        installedCommit = installedManifestProvider()?.source?.commit,
                        onPhase = onPhase,
                        onProgress = onProgress,
                    )
            }
        recordRefreshResult(result, liveActivation)
        return result.copy(liveActivation = liveActivation)
    }

    private suspend fun recordRefreshResult(
        result: DnsFilterUpdateResult,
        liveActivation: RuntimeDnsRuleSetInstallOutcome?,
    ) {
        when (result.status) {
            DnsFilterUpdateStatus.UPDATED -> {
                settingsRepository.markDnsFiltersUpdated()
                diagnosticsLogger.record(
                    "dns",
                    "filter update installed sourceCommit=${result.sourceCommit.orEmpty()} path=${result.installedPath.orEmpty()}",
                )
                when (val activation = liveActivation) {
                    is RuntimeDnsRuleSetInstallOutcome.Installed ->
                        diagnosticsLogger.record(
                            "dns",
                            "live filter activation revision=${activation.revision}",
                        )
                    RuntimeDnsRuleSetInstallOutcome.Deferred ->
                        diagnosticsLogger.record("dns", "live filter activation deferred until next start")
                    RuntimeDnsRuleSetInstallOutcome.Superseded ->
                        diagnosticsLogger.record("dns", "live filter activation superseded by runtime transition")
                    RuntimeDnsRuleSetInstallOutcome.Rejected ->
                        diagnosticsLogger.recordFailure("dns", "live filter activation rejected by FoxCore")
                    null ->
                        diagnosticsLogger.record("dns", "live filter activation was not attempted")
                }
            }
            DnsFilterUpdateStatus.UP_TO_DATE -> {
                settingsRepository.markDnsFiltersChecked()
                diagnosticsLogger.record(
                    "dns",
                    "filter update not needed sourceCommit=${result.sourceCommit.orEmpty()}",
                )
            }
            DnsFilterUpdateStatus.SKIPPED ->
                diagnosticsLogger.record("dns", "filter update skipped: ${result.reason.orEmpty()}")
            DnsFilterUpdateStatus.FAILED ->
                diagnosticsLogger.recordFailure(
                    "dns",
                    "filter update failed retryable=${result.retryable} error=${result.reason.orEmpty()}",
                )
        }
    }
}

internal class LiveActivatingDnsRuleSetStore(
    private val delegate: DnsFilterRuleSetStore,
    private val installer: suspend (
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
    ) -> RuntimeDnsRuleSetInstallOutcome,
    private val onOutcome: (RuntimeDnsRuleSetInstallOutcome) -> Unit,
) : DnsFilterRuleSetStore {
    override suspend fun installVerifiedDnsRuleSet(ruleSet: VerifiedDnsRuleSet): String {
        val path = delegate.installVerifiedDnsRuleSet(ruleSet)
        val outcome =
            runCatching {
                installer(
                    ruleSet.manifest.name,
                    ruleSet.manifestBytes,
                    ruleSet.signatureBytes,
                    ruleSet.artifactBytes,
                )
            }.getOrDefault(RuntimeDnsRuleSetInstallOutcome.Rejected)
        onOutcome(outcome)
        return path
    }
}
