package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeDnsRuleSetInstallOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class CategorizedDnsNativeActivation(
    val outcome: RuntimeDnsRuleSetInstallOutcome,
    val isOwnerCurrent: () -> Boolean,
)

internal suspend fun activateCategorizedDnsStage(
    stage: CategorizedDnsStage,
    bundle: VerifiedCategorizedDnsRuleSet,
    store: CategorizedDnsStagedStore,
    installer: suspend (CategorizedDnsStage, VerifiedCategorizedDnsRuleSet) -> CategorizedDnsNativeActivation,
): DnsFilterUpdateResult {
    currentCoroutineContext().ensureActive()
    val bytes = store.readBundle(stage)
    val capture = VerifiedCategorizedDnsRuleSet(bundle.manifest, bytes.manifest, bytes.signature, bytes.artifact)
    val activation = installer(stage, capture)
    val outcome = activation.outcome
    val variant = stage.name.removePrefix(CategorizedDnsContract.NAME_PREFIX)
    if (outcome is RuntimeDnsRuleSetInstallOutcome.Installed) {
        val promotion = store.promote(stage, activation.isOwnerCurrent)
        val active = promotion == CategorizedDnsPromotion.PROMOTED
        return DnsFilterUpdateResult(
            status = if (active) DnsFilterUpdateStatus.UPDATED else DnsFilterUpdateStatus.SKIPPED,
            installedPath = if (active) stage.directory.absolutePath else null,
            liveActivation = outcome,
            bundleState = when (promotion) {
                CategorizedDnsPromotion.PROMOTED -> DnsFilterBundleState.ACTIVE
                CategorizedDnsPromotion.SUPERSEDED -> DnsFilterBundleState.SUPERSEDED
                CategorizedDnsPromotion.PENDING_IO -> DnsFilterBundleState.PROMOTION_PENDING
            },
            reason = if (active) null else "categorized promotion requires current native owner",
            requestedVariant = variant,
            appliedVariant = if (active) variant else null,
        )
    }
    return DnsFilterUpdateResult(
        status = if (outcome == RuntimeDnsRuleSetInstallOutcome.Rejected) DnsFilterUpdateStatus.FAILED else DnsFilterUpdateStatus.SKIPPED,
        liveActivation = outcome,
        bundleState = when (outcome) {
            RuntimeDnsRuleSetInstallOutcome.Rejected -> DnsFilterBundleState.REJECTED
            RuntimeDnsRuleSetInstallOutcome.Superseded -> DnsFilterBundleState.SUPERSEDED
            else -> DnsFilterBundleState.STAGED
        },
        reason = "categorized bundle is not active",
        requestedVariant = variant,
    )
}
