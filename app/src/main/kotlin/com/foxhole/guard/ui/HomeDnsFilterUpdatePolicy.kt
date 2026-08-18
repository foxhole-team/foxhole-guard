package com.foxhole.guard.ui

import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.guard.runtime.DnsFilterUpdateStatus

internal fun dnsFilterRefreshAllowsRuntime(status: DnsFilterUpdateStatus): Boolean =
    status == DnsFilterUpdateStatus.UPDATED

internal fun dnsFilterDownloadCanEnable(status: DnsFilterUpdateStatus): Boolean =
    status == DnsFilterUpdateStatus.UPDATED || status == DnsFilterUpdateStatus.UP_TO_DATE

internal fun shouldPreflightDnsRuleSetEnable(
    current: DnsSettings,
    next: DnsSettings,
): Boolean =
    !current.dnsRuleSetFilteringEnabled() &&
        next.dnsRuleSetFilteringEnabled()

internal fun dnsFilterTerminalPhase(status: DnsFilterUpdateStatus): FoxholeUpdatePhase =
    when (status) {
        DnsFilterUpdateStatus.UPDATED -> FoxholeUpdatePhase.DONE
        DnsFilterUpdateStatus.UP_TO_DATE -> FoxholeUpdatePhase.NO_UPDATE
        DnsFilterUpdateStatus.SKIPPED, DnsFilterUpdateStatus.FAILED -> FoxholeUpdatePhase.FAILED
    }

internal fun dnsFilterVerifiedTerminalPhase(
    status: DnsFilterUpdateStatus,
    verifiedRuleSetReady: Boolean,
): FoxholeUpdatePhase =
    if (verifiedRuleSetReady) dnsFilterTerminalPhase(status) else FoxholeUpdatePhase.FAILED

internal fun shouldReloadRuntimeAfterDnsRuleSetEnable(dnsSettings: DnsSettings): Boolean =
    dnsSettings.dnsRuleSetFilteringEnabled()
