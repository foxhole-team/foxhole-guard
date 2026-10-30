package com.foxhole.guard.ui

import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.core.model.requestedDnsRuleSetTags
import com.foxhole.guard.runtime.CategorizedDnsPreparation
import com.foxhole.guard.runtime.DnsFilterBundleState
import com.foxhole.guard.runtime.DnsFilterUpdateResult
import com.foxhole.guard.runtime.DnsFilterUpdateStatus
import com.foxhole.guard.runtime.categorizedDnsPreparationReady
import com.foxhole.guard.runtime.categorizedDnsSelection

internal fun dnsFilterRefreshAllowsRuntime(status: DnsFilterUpdateStatus): Boolean =
    status == DnsFilterUpdateStatus.UPDATED

internal fun dnsFilterDownloadCanEnable(status: DnsFilterUpdateStatus): Boolean =
    status == DnsFilterUpdateStatus.UPDATED || status == DnsFilterUpdateStatus.UP_TO_DATE

internal fun shouldPreflightDnsRuleSetEnable(
    current: DnsSettings,
    next: DnsSettings,
): Boolean =
    next.dnsRuleSetFilteringEnabled() &&
        (
            !current.dnsRuleSetFilteringEnabled() || current.requestedDnsRuleSetTags() != next.requestedDnsRuleSetTags() ||
                current.trackerListLevel != next.trackerListLevel || current.threatListLevel != next.threatListLevel
            )

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

internal enum class DnsCategoryRefreshDisposition {
    ACTIVE_UPDATED, ACTIVE_CURRENT, LEGACY_UPDATED, LEGACY_CURRENT, PREPARED_PENDING, FAILED;

    val active: Boolean get() = this == ACTIVE_UPDATED || this == ACTIVE_CURRENT
    val phase: FoxholeUpdatePhase get() = when (this) {
        ACTIVE_UPDATED, LEGACY_UPDATED -> FoxholeUpdatePhase.DONE
        ACTIVE_CURRENT, LEGACY_CURRENT -> FoxholeUpdatePhase.NO_UPDATE
        PREPARED_PENDING -> FoxholeUpdatePhase.IDLE
        FAILED -> FoxholeUpdatePhase.FAILED
    }
}

internal data class DnsCategoryReadiness(val prepared: Boolean, val disposition: DnsCategoryRefreshDisposition) {
    val active: Boolean get() = disposition.active
}

internal suspend fun prepareDnsCategoryReadiness(
    settings: DnsSettings,
    prepare: suspend () -> CategorizedDnsPreparation,
    refresh: suspend () -> DnsFilterUpdateResult,
): DnsCategoryReadiness {
    if (categorizedDnsPreparationReady(settings, prepare())) {
        return DnsCategoryReadiness(true, DnsCategoryRefreshDisposition.PREPARED_PENDING)
    }
    val result = refresh()
    return dnsCategoryReadiness(settings, prepare(), result)
}

internal fun dnsCategoryReadiness(
    settings: DnsSettings,
    preparation: CategorizedDnsPreparation,
    result: DnsFilterUpdateResult,
): DnsCategoryReadiness {
    val disposition = dnsCategoryRefreshDisposition(
        result,
        categorizedDnsPreparationReady(settings, preparation),
        settings.categorizedDnsVariant(),
    )
    return DnsCategoryReadiness(disposition != DnsCategoryRefreshDisposition.FAILED, disposition)
}

internal fun dnsCategoryRefreshDisposition(
    result: DnsFilterUpdateResult,
    selectedReady: Boolean,
    expectedVariant: String,
): DnsCategoryRefreshDisposition {
    if (!selectedReady || result.status == DnsFilterUpdateStatus.FAILED) return DnsCategoryRefreshDisposition.FAILED
    if (result.requestedVariant != null && result.requestedVariant != expectedVariant) return DnsCategoryRefreshDisposition.FAILED
    if (result.appliedVariant != null && result.appliedVariant != expectedVariant) return DnsCategoryRefreshDisposition.FAILED
    return when (result.bundleState) {
        DnsFilterBundleState.STAGED,
        DnsFilterBundleState.PROMOTION_PENDING -> DnsCategoryRefreshDisposition.PREPARED_PENDING
        DnsFilterBundleState.SUPERSEDED,
        DnsFilterBundleState.REJECTED -> DnsCategoryRefreshDisposition.FAILED
        DnsFilterBundleState.ACTIVE -> activeDnsCategoryRefreshDisposition(result, expectedVariant)
        null -> legacyDnsCategoryRefreshDisposition(result.status)
    }
}

private fun activeDnsCategoryRefreshDisposition(result: DnsFilterUpdateResult, expectedVariant: String): DnsCategoryRefreshDisposition = when {
    result.appliedVariant != expectedVariant -> DnsCategoryRefreshDisposition.FAILED
    result.status == DnsFilterUpdateStatus.UPDATED -> DnsCategoryRefreshDisposition.ACTIVE_UPDATED
    result.status == DnsFilterUpdateStatus.UP_TO_DATE -> DnsCategoryRefreshDisposition.ACTIVE_CURRENT
    else -> DnsCategoryRefreshDisposition.FAILED
}

private fun legacyDnsCategoryRefreshDisposition(status: DnsFilterUpdateStatus): DnsCategoryRefreshDisposition = when (status) {
    DnsFilterUpdateStatus.UPDATED -> DnsCategoryRefreshDisposition.LEGACY_UPDATED
    DnsFilterUpdateStatus.UP_TO_DATE -> DnsCategoryRefreshDisposition.LEGACY_CURRENT
    else -> DnsCategoryRefreshDisposition.FAILED
}

private fun DnsSettings.categorizedDnsVariant(): String =
    categorizedDnsSelection().variant
