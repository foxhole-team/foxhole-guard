package com.foxhole.guard.core.sentinel.scanner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SentinelScanControllerTest {
    @Test fun importedPolicyChangeBeforeObserverRunsCannotPersistOldResult() = runBlocking {
        val f = fixture()
        f.pause = true
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        val scan = async { f.controller.requestPackage("org.owned.app") }
        withTimeout(3000) { f.entered.await() }
        f.policyCurrent = false
        f.release.complete(Unit)
        scan.await()
        assertEquals(0, f.persisted)
        assertEquals(SentinelScanStage.STALE, f.controller.state.value.stage)
    }

    @Test fun disabledMasterDoesNotCapturePackagesOrRunEngine() = runBlocking {
        val f = fixture()
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = false))
        f.controller.requestPackage("org.owned.app")
        assertEquals(0, f.captures)
        assertEquals(0, f.scans)
        assertEquals(SentinelScanStage.DISABLED, f.controller.state.value.stage)
    }

    @Test fun manualRequestAllowsExplicitScanWithoutEnablingAutomaticHeavyPolicy() = runBlocking {
        val f = fixture()
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        f.controller.requestPackage("org.owned.app")
        assertEquals(1, f.scans)
        assertEquals(SentinelScanStage.COMPLETE, f.controller.state.value.stage)
    }

    @Test fun completeCurrentIdentityIsCachedButPartialResultNeverIs() = runBlocking {
        val f = fixture()
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        repeat(2) { f.controller.requestPackage("org.owned.app") }
        assertEquals(1, f.scans)
        f.identity = "install:2"
        f.complete = false
        repeat(2) { f.controller.requestPackage("org.owned.app") }
        assertEquals(3, f.scans)
        assertEquals(SentinelScanStage.PARTIAL, f.controller.state.value.stage)
    }

    @Test fun replacementDuringScanCannotPublishOrPersistItsResult() = runBlocking {
        val f = fixture()
        f.pause = true
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        val scan = async { f.controller.requestPackage("org.owned.app") }
        withTimeout(3000) { f.entered.await() }
        f.identity = "install:2"
        f.release.complete(Unit)
        scan.await()
        assertEquals(0, f.persisted)
        assertEquals(SentinelScanStage.STALE, f.controller.state.value.stage)
    }

    @Test fun policyDisableCancelsRunningScanAndCannotPublishLateResult() = runBlocking {
        val f = fixture()
        f.pause = true
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        val scan = async { f.controller.requestPackage("org.owned.app") }
        withTimeout(3000) { f.entered.await() }
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = false))
        f.release.complete(Unit)
        scan.await()
        assertEquals(0, f.persisted)
        assertEquals(SentinelScanStage.DISABLED, f.controller.state.value.stage)
    }

    @Test fun automaticRequestWaitsForConfiguredEnergyConditions() = runBlocking {
        val f = fixture()
        f.controller.updatePolicy(
            SentinelScanWorkPolicy(masterEnabled = true, dexEnabled = true, automaticNewUpdatedApps = true)
        )
        f.environment = SentinelScanEnvironment(charging = false, batteryNotLow = true, storageNotLow = true)
        f.controller.requestAutomaticPackage("org.owned.app")
        assertEquals(0, f.scans)
        assertEquals(SentinelScanStage.WAITING, f.controller.state.value.stage)
        f.environment = f.environment.copy(charging = true)
        f.controller.requestAutomaticPackage("org.owned.app")
        assertEquals(1, f.scans)
    }

    @Test fun visibleBaselineIsBoundedAndDuplicatePackagesDoNotRescan() = runBlocking {
        val f = fixture()
        f.packages = listOf("org.owned.app", "org.owned.app", "org.owned.other")
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        f.controller.requestVisibleBaseline()
        assertEquals(2, f.scans)
        assertEquals(2, f.controller.state.value.completedPackages)
    }

    @Test fun disabledMasterDoesNotEvenEnumerateVisibleBaseline() = runBlocking {
        val f = fixture()
        f.controller.requestVisibleBaseline()
        assertEquals(0, f.enumerations)
        assertEquals(0, f.scans)
    }

    @Test fun explicitCancelDoesNotLaunchPreviouslyQueuedRequests() = runBlocking {
        val f = fixture()
        f.pause = true
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        val first = async { f.controller.requestPackage("org.owned.first") }
        withTimeout(3000) { f.entered.await() }
        val queued = async { f.controller.requestPackage("org.owned.second") }
        kotlinx.coroutines.yield()
        f.controller.cancel()
        f.release.complete(Unit)
        first.await()
        queued.await()
        assertEquals(1, f.scans)
        assertEquals(SentinelScanStage.CANCELLED, f.controller.state.value.stage)
    }

    @Test fun rejectedPersistentOwnerCannotEnterCompleteCache() = runBlocking {
        val f = fixture()
        f.acceptPersist = false
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        repeat(2) { f.controller.requestPackage("org.owned.app") }
        assertEquals(2, f.scans)
        assertEquals(0, f.persisted)
        assertFalse(f.controller.state.value.stage == SentinelScanStage.COMPLETE)
    }

    @Test fun changedRuleEpochAndScanCostInvalidateCachedCoverage() = runBlocking {
        val f = fixture()
        val original = SentinelScanWorkPolicy(masterEnabled = true)
        f.controller.updatePolicy(original)
        f.controller.requestPackage("org.owned.app")
        f.controller.updatePolicy(original.copy(ruleEpoch = "rules:2"))
        f.controller.requestPackage("org.owned.app")
        f.controller.updatePolicy(original.copy(ruleEpoch = "rules:2", maxArtifactBytes = 64 * 1024 * 1024))
        f.controller.requestPackage("org.owned.app")
        assertEquals(3, f.scans)
    }

    @Test fun lateResultFromCancelledBackendCannotPersistEvenWhenBackendIgnoresCancellation() = runBlocking {
        val f = fixture()
        f.pause = true
        f.ignoreCancellation = true
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        val active = async { f.controller.requestPackage("org.owned.app") }
        withTimeout(3000) { f.entered.await() }
        f.controller.cancel()
        f.release.complete(Unit)
        active.await()
        assertEquals(0, f.persisted)
        assertEquals(SentinelScanStage.CANCELLED, f.controller.state.value.stage)
    }

    @Test fun unavailableBackendCoverageNeverAppearsAsPartialOrComplete() = runBlocking {
        val f = fixture()
        f.complete = false
        f.outcome = SentinelScanStage.UNAVAILABLE
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        f.controller.requestPackage("org.owned.app")
        assertEquals(SentinelScanStage.UNAVAILABLE, f.controller.state.value.stage)
    }

    @Test fun unavailablePackageDoesNotAbortAllRemainingVisiblePackages() = runBlocking {
        val f = fixture()
        f.packages = listOf("org.owned.missing", "org.owned.app")
        f.missing = "org.owned.missing"
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        f.controller.requestVisibleBaseline()
        assertEquals(1, f.scans)
        assertEquals(2, f.controller.state.value.completedPackages)
        assertEquals(SentinelScanStage.COMPLETE, f.controller.state.value.stage)
    }

    @Test fun lockedJournalIsExplicitAndReusedCoverageCanPersistAfterUnlock() = runBlocking {
        val f = fixture()
        f.journalBlocked = true
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        f.controller.requestPackage("org.owned.app")
        assertTrue(f.controller.state.value.journalDeferred)
        assertEquals(0, f.persisted)
        f.journalBlocked = false
        f.controller.requestPackage("org.owned.app")
        assertFalse(f.controller.state.value.journalDeferred)
        assertEquals(1, f.scans)
        assertEquals(1, f.persisted)
    }

    @Test fun manualRequestDoesNotStartWhenDeviceStorageIsLow() = runBlocking {
        val f = fixture()
        f.environment = f.environment.copy(storageNotLow = false)
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        f.controller.requestPackage("org.owned.app")
        assertEquals(0, f.scans)
        assertEquals(SentinelScanStage.WAITING, f.controller.state.value.stage)
    }

    @Test fun manualRequestRefusesCriticalBatteryEvenWithChargingRuleDisabled() = runBlocking {
        val f = fixture()
        f.environment = f.environment.copy(criticalBattery = true)
        f.controller.updatePolicy(
            SentinelScanWorkPolicy(masterEnabled = true, chargingOnly = false, batteryNotLow = false)
        )
        f.controller.requestPackage("org.owned.app")
        assertEquals(0, f.scans)
        assertEquals(SentinelScanStage.WAITING, f.controller.state.value.stage)
    }

    @Test fun automaticQueueLosingChargingCannotPublishOrContinue() = runBlocking {
        val f = fixture()
        f.pause = true
        f.packages = listOf("org.owned.first", "org.owned.second")
        f.controller.updatePolicy(
            SentinelScanWorkPolicy(masterEnabled = true, dexEnabled = true, automaticNewUpdatedApps = true)
        )
        val active = async { f.controller.requestAutomaticBaseline() }
        withTimeout(3000) { f.entered.await() }
        f.environment = f.environment.copy(charging = false)
        f.release.complete(Unit)
        active.await()
        assertEquals(1, f.scans)
        assertEquals(0, f.persisted)
        assertEquals(SentinelScanStage.WAITING, f.controller.state.value.stage)
    }

    @Test fun manualQueueStopsBeforeNextPackageWhenStorageFallsLow() = runBlocking {
        val f = fixture()
        f.packages = (1..100).map { "org.owned.app$it" }
        f.onPersist = { f.environment = f.environment.copy(storageNotLow = false) }
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true))
        f.controller.requestVisibleBaseline()
        assertEquals(1, f.scans)
        assertEquals(1, f.persisted)
        assertEquals(SentinelScanStage.WAITING, f.controller.state.value.stage)
    }

    @Test fun automaticNativeOwnerRefusalAfterConstraintLossRemainsRetryable() = runBlocking {
        val f = fixture()
        f.pause = true
        f.rejectObsoleteOwner = true
        f.controller.updatePolicy(
            SentinelScanWorkPolicy(masterEnabled = true, dexEnabled = true, automaticNewUpdatedApps = true)
        )
        val active = async { f.controller.requestAutomaticPackage("org.owned.app") }
        withTimeout(3000) { f.entered.await() }
        f.environment = f.environment.copy(charging = false)
        f.release.complete(Unit)
        active.await()
        assertEquals(0, f.persisted)
        assertEquals(SentinelScanStage.WAITING, f.controller.state.value.stage)
    }

    @Test fun manualRequestMayRunWithoutChargingWhenBatteryIsNotCritical() = runBlocking {
        val f = fixture()
        f.environment = f.environment.copy(charging = false)
        f.controller.updatePolicy(SentinelScanWorkPolicy(masterEnabled = true, chargingOnly = true))
        f.controller.requestPackage("org.owned.app")
        assertEquals(1, f.scans)
        assertEquals(1, f.persisted)
    }

    private fun fixture() = Fixture()
    private class Fixture {
        var missing: String? = null
        var enumerations = 0
        var captures = 0
        var scans = 0
        var journalBlocked = false
        var acceptPersist = true
        var persisted = 0
        var onPersist: () -> Unit = {}
        var identity = "install:1"
        var outcome: SentinelScanStage? = null
        var complete = true
        var ignoreCancellation = false
        var rejectObsoleteOwner = false
        var pause = false
        var packages = listOf("org.owned.app")
        var environment = SentinelScanEnvironment(true, true, true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var policyCurrent = true
        val controller = SentinelScanController(
            policyCurrent = { policyCurrent },
            capture = { packageName ->
                captures++
                if (packageName == missing) throw IOException("owned missing package")
                SentinelScanSource(packageName, identity)
            },
            isCurrent = { it.installationId == identity },
            visiblePackages = {
                enumerations++
                packages
            },
            environment = { environment },
            scan = { source, _, current ->
                scans++
                if (pause) {
                    entered.complete(Unit)
                    if (ignoreCancellation) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                    } else {
                        release.await()
                    }
                }
                if (rejectObsoleteOwner && !current()) throw IOException("Scanner owner changed")
                SentinelScanSummary(
                    source,
                    complete = complete,
                    observations = emptySet(),
                    ruleIds = emptySet(),
                    outcome = outcome ?: if (complete) SentinelScanStage.COMPLETE else SentinelScanStage.PARTIAL
                )
            },
            persist = { _, current ->
                when {
                    !acceptPersist || !current() -> ScannerJournalOutcome.REJECTED
                    journalBlocked -> ScannerJournalOutcome.DEFERRED
                    else -> {
                        persisted++
                        onPersist()
                        ScannerJournalOutcome.COMMITTED
                    }
                }
            },
        )
    }
}
