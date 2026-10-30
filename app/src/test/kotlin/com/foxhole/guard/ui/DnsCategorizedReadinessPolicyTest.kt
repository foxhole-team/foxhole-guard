package com.foxhole.guard.ui

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.ThreatListLevel
import com.foxhole.core.model.TrackerListLevel
import com.foxhole.core.runtime.DnsFilterRuntimePaths
import com.foxhole.guard.R
import com.foxhole.guard.runtime.CategorizedDnsPreparation
import com.foxhole.guard.runtime.CategorizedDnsUnavailable
import com.foxhole.guard.runtime.DnsFilterBundleState
import com.foxhole.guard.runtime.DnsFilterUnavailableException
import com.foxhole.guard.runtime.DnsFilterUpdateResult
import com.foxhole.guard.runtime.DnsFilterUpdateStatus
import com.foxhole.guard.runtime.dnsCategoryAttempt
import com.foxhole.guard.runtime.requiredCategorizedDnsRuntimePaths
import com.foxhole.guard.userFacingErrorMessageRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCategorizedReadinessPolicyTest {
    @Test fun enabledCategoryAndVariantChangesRequirePreflight() {
        val current = DnsSettings(filteringEnabled = true)
        assertTrue(shouldPreflightDnsRuleSetEnable(current, current.copy(blockAds = false)))
        assertTrue(shouldPreflightDnsRuleSetEnable(current, current.copy(trackerListLevel = TrackerListLevel.PRO)))
        assertTrue(shouldPreflightDnsRuleSetEnable(current, current.copy(threatListLevel = ThreatListLevel.FULL)))
        assertFalse(shouldPreflightDnsRuleSetEnable(current, current.copy(server = "9.9.9.9")))
        assertFalse(shouldPreflightDnsRuleSetEnable(current, current.copy(filteringEnabled = false)))
    }

    @Test fun readyLocalSelectedDataAvoidsAnyNetworkRefresh() = runBlocking {
        val settings = DnsSettings(filteringEnabled = true)
        var downloads = 0
        val result = prepareDnsCategoryReadiness(
            settings,
            { ready() },
            {
                downloads++
                throw AssertionError("network must not be required")
            }
        )
        assertEquals(0, downloads)
        assertTrue(result.prepared)
        assertFalse(result.active)
        assertEquals(DnsCategoryRefreshDisposition.PREPARED_PENDING, result.disposition)
    }

    @Test fun staleVariantOrUnavailableSelectedCategoryFetchesAndRechecks() = runBlocking {
        val settings = DnsSettings(filteringEnabled = true)
        var prepares = 0
        var downloads = 0
        val result = prepareDnsCategoryReadiness(
            settings,
            {
                prepares++
                if (prepares == 1) ready("pro-full") else ready()
            },
            {
                downloads++
                update(DnsFilterBundleState.STAGED, DnsFilterUpdateStatus.SKIPPED)
            },
        )
        assertEquals(2, prepares)
        assertEquals(1, downloads)
        assertTrue(result.prepared)
        assertEquals(DnsCategoryRefreshDisposition.PREPARED_PENDING, result.disposition)
    }

    @Test fun stagedAndPromotionPendingNeverClaimUpdatedOrActive() {
        for (state in listOf(DnsFilterBundleState.STAGED, DnsFilterBundleState.PROMOTION_PENDING)) {
            val disposition = dnsCategoryRefreshDisposition(
                update(state, DnsFilterUpdateStatus.UPDATED),
                true,
                "normal-medium"
            )
            assertEquals(DnsCategoryRefreshDisposition.PREPARED_PENDING, disposition)
            assertFalse(disposition.active)
            assertEquals(FoxholeUpdatePhase.IDLE, disposition.phase)
        }
    }

    @Test fun rejectionSupersessionFailureAndVariantMismatchStayNonSuccess() {
        for (state in listOf(DnsFilterBundleState.REJECTED, DnsFilterBundleState.SUPERSEDED)) {
            assertEquals(
                DnsCategoryRefreshDisposition.FAILED,
                dnsCategoryRefreshDisposition(update(state, DnsFilterUpdateStatus.UPDATED), true, "normal-medium")
            )
        }
        assertEquals(
            DnsCategoryRefreshDisposition.FAILED,
            dnsCategoryRefreshDisposition(
                update(DnsFilterBundleState.STAGED, DnsFilterUpdateStatus.FAILED),
                true,
                "normal-medium"
            )
        )
        assertEquals(
            DnsCategoryRefreshDisposition.FAILED,
            dnsCategoryRefreshDisposition(
                update(DnsFilterBundleState.ACTIVE).copy(appliedVariant = "pro-full"),
                true,
                "normal-medium"
            )
        )
        assertEquals(
            DnsCategoryRefreshDisposition.FAILED,
            dnsCategoryRefreshDisposition(update(DnsFilterBundleState.ACTIVE), false, "normal-medium")
        )
    }

    @Test fun activeAndLegacyVerifiedResponsesHaveDistinctTerminalPolicy() {
        val active = dnsCategoryRefreshDisposition(update(DnsFilterBundleState.ACTIVE), true, "normal-medium")
        assertTrue(active.active)
        assertEquals(FoxholeUpdatePhase.DONE, active.phase)
        val current = dnsCategoryRefreshDisposition(
            DnsFilterUpdateResult(DnsFilterUpdateStatus.UP_TO_DATE),
            true,
            "normal-medium"
        )
        assertEquals(FoxholeUpdatePhase.NO_UPDATE, current.phase)
        assertEquals(
            DnsCategoryRefreshDisposition.FAILED,
            dnsCategoryRefreshDisposition(DnsFilterUpdateResult(DnsFilterUpdateStatus.SKIPPED), true, "normal-medium")
        )
    }

    @Test fun unavailableAfterRefreshNeverEnablesRequestedFiltering() = runBlocking {
        val settings = DnsSettings(filteringEnabled = true)
        val result = prepareDnsCategoryReadiness(
            settings,
            { CategorizedDnsPreparation.Unavailable(CategorizedDnsUnavailable.CATALOG_INVALID) },
            { update(DnsFilterBundleState.STAGED) }
        )
        assertFalse(result.prepared)
        assertEquals(DnsCategoryRefreshDisposition.FAILED, result.disposition)
    }

    @Test fun coldRuntimeThrowsTypedUnavailableInsteadOfDisablingOrRawText() {
        val settings = DnsSettings(filteringEnabled = true)
        for (reason in CategorizedDnsUnavailable.entries) {
            val error = try {
                requiredCategorizedDnsRuntimePaths(settings, CategorizedDnsPreparation.Unavailable(reason))
                throw AssertionError("requested data must not silently disable")
            } catch (failure: DnsFilterUnavailableException) { failure }
            assertEquals(reason, error.reason)
            assertTrue(
                userFacingErrorMessageRes(IllegalStateException("wrapper", error), R.string.error_runtime_start_failed) != R.string.error_runtime_start_failed
            )
        }
        val prepared = ready() as CategorizedDnsPreparation.Ready
        assertSame(prepared.paths, requiredCategorizedDnsRuntimePaths(settings, prepared))
    }

    @Test fun disabledRuntimeDoesNotRequireAnyFilterBootstrap() {
        assertEquals(null, requiredCategorizedDnsRuntimePaths(DnsSettings(), CategorizedDnsPreparation.Disabled))
        val noCategories = DnsSettings(
            filteringEnabled = true,
            blockAds = false,
            blockTrackers = false,
            blockAppTelemetry = false,
            blockMaliciousDomains = false
        )
        assertFalse(shouldPreflightDnsRuleSetEnable(DnsSettings(), noCategories))
        assertEquals(null, requiredCategorizedDnsRuntimePaths(noCategories, CategorizedDnsPreparation.Disabled))
    }

    @Test fun cancellationAndFatalErrorsEscapeTheAffectedAttemptBoundary() {
        val cancellation = CancellationException("owned cancellation")
        val fatal = AssertionError("owned fatal")
        for (failure in listOf(cancellation, fatal)) {
            val thrown = try {
                dnsCategoryAttempt<Unit> { throw failure }
                null
            } catch (error: Throwable) { error }
            assertSame(failure, thrown)
        }
        assertTrue(dnsCategoryAttempt { throw java.io.IOException("owned IO") }.isFailure)
    }

    @Test fun preparedOnboardingCanEnableWithoutClaimingActiveCompletion() {
        val state = OnboardingDownloadState(
            OnboardingDownload.DNS_FILTER,
            null,
            dnsPreparedForRuntimeVerification = true
        )
        val progress = OnboardingProgress(finished = true, items = listOf(state))
        assertFalse(state.done)
        assertTrue(onboardingDnsFilterCanEnable(true, progress))
        assertEquals(1f, progress.fraction, 0f)
        assertFalse(onboardingDnsFilterCanEnable(false, progress))
    }

    @Test fun typedUnavailableReasonsUseDistinctLocalizedResourcesAndKeepOldFailurePriority() {
        val reasons = CategorizedDnsUnavailable.entries.map { reason ->
            userFacingErrorMessageRes(DnsFilterUnavailableException(reason), R.string.error_runtime_start_failed)
        }
        assertEquals(4, reasons.toSet().size)
        assertEquals(
            listOf(
                R.string.dns_category_catalog_missing,
                R.string.dns_category_catalog_invalid,
                R.string.dns_category_required_missing,
                R.string.dns_category_signed_invalid
            ),
            reasons
        )
        assertEquals(
            R.string.vpn_error_server_timeout,
            userFacingErrorMessageRes(
                java.net.ConnectException("connection refused"),
                R.string.error_runtime_start_failed
            )
        )
        assertEquals(
            R.string.error_dns_probe_failed,
            userFacingErrorMessageRes(java.net.UnknownHostException("owned DNS"), R.string.error_runtime_start_failed)
        )
    }

    @Test fun defaultOnboardingShapeKeepsExistingVerifiedRowsAndFailedPendingCannotEnable() {
        val oldRow = OnboardingDownloadState(OnboardingDownload.DNS_FILTER, null, done = true)
        assertFalse(oldRow.dnsPreparedForRuntimeVerification)
        assertTrue(onboardingDnsFilterCanEnable(true, OnboardingProgress(finished = true, items = listOf(oldRow))))
        val failedPending = oldRow.copy(done = false, failed = true, dnsPreparedForRuntimeVerification = true)
        assertFalse(
            onboardingDnsFilterCanEnable(true, OnboardingProgress(finished = true, items = listOf(failedPending)))
        )
    }

    private fun update(state: DnsFilterBundleState, status: DnsFilterUpdateStatus = DnsFilterUpdateStatus.UPDATED): DnsFilterUpdateResult =
        DnsFilterUpdateResult(
            status,
            bundleState = state,
            requestedVariant = "normal-medium",
            appliedVariant = if (state == DnsFilterBundleState.ACTIVE) "normal-medium" else null
        )

    private fun ready(variant: String = "normal-medium"): CategorizedDnsPreparation = CategorizedDnsPreparation.Ready(
        DnsFilterRuntimePaths(
            "owned",
            FoxCoreDnsRuleSetBootstrap(
                "owned",
                "owned",
                "00".repeat(32),
                "owned",
                availableCategories = DnsFilterCategory.entries.toSet(),
                categoryVariant = variant
            )
        ),
    )
}
