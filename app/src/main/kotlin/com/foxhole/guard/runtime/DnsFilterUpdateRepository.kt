package com.foxhole.guard.runtime

import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.core.model.requestedDnsRuleSetTags
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.core.runtime.RuntimeSettings

class DnsFilterUpdateRepository(
    private val settingsRepository: RuntimeSettings,
    private val client: DnsFilterUpdateClient,
    private val store: DnsFilterRuleSetStore,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val installedManifestProvider: suspend () -> DnsFilterManifest? = { null },
    private val installedRuleSetsProvider: suspend () -> InstalledDnsRuleSets = { InstalledDnsRuleSets() },
) {
    // Probes the update source for a newer rule set without downloading any artifact. On the
    // schema-2 channel "newer" is per list: a level switch (say trackers Normal -> Pro) asks for a
    // different list and shows up here as an available update.
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

    /**
     * Scheduled auto-update pass: a cheap manifest probe first, the full artifact download only
     * when a newer list is published — plus a forced full refresh once the installed list is older
     * than [forcedRefreshIntervalMs], so a device that keeps missing probes still converges.
     */
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
        val result =
            when {
                !dnsSettings.dnsRuleSetFilteringEnabled() ->
                    DnsFilterUpdateResult(status = DnsFilterUpdateStatus.SKIPPED, reason = "filtering disabled")
                requireAutoEnabled && !dnsSettings.autoUpdateFilters ->
                    DnsFilterUpdateResult(status = DnsFilterUpdateStatus.SKIPPED, reason = "automatic updates disabled")
                else ->
                    client.update(
                        manifestUrl = dnsSettings.dnsFilterUpdateUrl,
                        store = store,
                        requestedTags = dnsSettings.requestedDnsRuleSetTags(),
                        installedRuleSets = installedRuleSetsProvider(),
                        installedCommit = installedManifestProvider()?.source?.commit,
                        onPhase = onPhase,
                        onProgress = onProgress,
                    )
            }
        when (result.status) {
            DnsFilterUpdateStatus.UPDATED -> {
                settingsRepository.markDnsFiltersUpdated()
                diagnosticsLogger.record(
                    "dns",
                    "filter update installed sourceCommit=${result.sourceCommit.orEmpty()} path=${result.installedPath.orEmpty()}",
                )
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
        return result
    }
}
