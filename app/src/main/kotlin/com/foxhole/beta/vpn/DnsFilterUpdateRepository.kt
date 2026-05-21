package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.beta.core.settings.SettingsRepository

class DnsFilterUpdateRepository(
    private val settingsRepository: SettingsRepository,
    private val client: DnsFilterUpdateClient,
    private val store: DnsFilterRuleSetStore,
    private val diagnosticsLogger: DiagnosticsLogger,
) {
    suspend fun refreshNow(requireAutoEnabled: Boolean): DnsFilterUpdateResult {
        val dnsSettings = settingsRepository.current().dns
        if (!dnsSettings.dnsRuleSetFilteringEnabled()) {
            diagnosticsLogger.record("dns", "filter update skipped: DNS rule-set filtering disabled")
            return DnsFilterUpdateResult(status = DnsFilterUpdateStatus.SKIPPED, reason = "filtering disabled")
        }
        if (requireAutoEnabled && !dnsSettings.autoUpdateFilters) {
            diagnosticsLogger.record("dns", "filter update skipped: automatic updates disabled")
            return DnsFilterUpdateResult(status = DnsFilterUpdateStatus.SKIPPED, reason = "automatic updates disabled")
        }
        val result = client.update(dnsSettings.dnsFilterUpdateUrl, store)
        when (result.status) {
            DnsFilterUpdateStatus.UPDATED -> {
                settingsRepository.markDnsFiltersUpdated()
                diagnosticsLogger.record(
                    "dns",
                    "filter update installed sourceCommit=${result.sourceCommit.orEmpty()} path=${result.installedPath.orEmpty()}",
                )
            }
            DnsFilterUpdateStatus.SKIPPED ->
                diagnosticsLogger.record("dns", "filter update skipped: ${result.reason.orEmpty()}")
            DnsFilterUpdateStatus.FAILED ->
                diagnosticsLogger.record(
                    "dns",
                    "filter update failed retryable=${result.retryable} error=${result.reason.orEmpty()}",
                )
        }
        return result
    }
}
