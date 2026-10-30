package com.foxhole.guard.guardian

import android.content.Context
import android.content.ContextWrapper
import com.foxhole.guard.core.sentinel.FileCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GuardRepairEnergyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cipherCancellationCannotBeMaskedByOwnedStagingCleanup() {
        val file = File(temporary.newFolder(), "inventory.bin")
        val failure = OwnedInventoryCancellation()
        val cipher = object : FileCipher {
            override fun readBytes(file: File): ByteArray = file.readBytes()
            override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
                file.writeBytes(plaintext)
                val blocked = File(file.parentFile, file.name + ".previous").apply { mkdirs() }
                File(blocked, "owned-blocker").writeText("fixture")
                throw failure
            }
        }
        val store = GuardInventorySnapshotStore(file, cipher)
        assertSame(
            failure,
            runCatching { store.write(GuardInventorySnapshot(lastAuthoritativeFullRepairAt = 1000)) }.exceptionOrNull()
        )
    }

    @Test fun cipherFatalRetainsIdentityAndSecondaryCleanupFailure() {
        val file = File(temporary.newFolder(), "inventory.bin")
        val failure = OwnedInventoryFatal()
        val cipher = object : FileCipher {
            override fun readBytes(file: File): ByteArray = file.readBytes()
            override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
                file.writeBytes(plaintext)
                val blocked = File(file.parentFile, file.name + ".previous").apply { mkdirs() }
                File(blocked, "owned-blocker").writeText("fixture")
                throw failure
            }
        }
        assertSame(
            failure,
            runCatching { GuardInventorySnapshotStore(file, cipher).write(GuardInventorySnapshot()) }.exceptionOrNull()
        )
        assertTrue(failure.suppressed.isNotEmpty())
    }

    @Test fun failedRollbackKeepsEncryptedPreviousUntilReopenCanRecover() {
        val file = File(temporary.newFolder(), "inventory.bin")
        val cipher = object : FileCipher {
            override fun readBytes(file: File): ByteArray = file.readBytes()
            override fun writeBytesAtomic(file: File, plaintext: ByteArray) { file.writeBytes(plaintext) }
        }
        val store = GuardInventorySnapshotStore(file, cipher)
        store.write(GuardInventorySnapshot(lastAuthoritativeFullRepairAt = 1000))
        var checks = 0
        val failed = runCatching {
            store.writeIfCurrent(GuardInventorySnapshot(lastAuthoritativeFullRepairAt = 1001)) {
                if (++checks == 3) {
                    check(file.delete())
                    check(file.mkdir())
                    File(file, "owned-blocker").writeText("fixture")
                    false
                } else {
                    true
                }
            }
        }
        assertTrue(failed.isFailure)
        val parent = requireNotNull(file.parentFile)
        val staged = parent.listFiles().orEmpty().single { it.name.startsWith(".inventory.bin.stage-") }
        assertTrue(File(staged, "inventory.bin.previous").isFile)
        assertTrue(File(staged, "rollback").isFile)
        assertTrue(runCatching { GuardInventorySnapshotStore(file, cipher).read() }.isFailure)
        check(file.deleteRecursively())
        assertEquals(1000, GuardInventorySnapshotStore(file, cipher).read().lastAuthoritativeFullRepairAt)
        assertFalse(staged.exists())
    }

    @Test fun recoveryAfterInterruptedUnacceptedWriteRestoresPreviousAndIsIdempotent() {
        val file = File(temporary.newFolder(), "inventory.bin")
        val cipher = OwnedCipher()
        val store = GuardInventorySnapshotStore(file, cipher)
        store.write(GuardInventorySnapshot(lastAuthoritativeFullRepairAt = 1000))
        val previous = file.readBytes()
        store.write(GuardInventorySnapshot(lastAuthoritativeFullRepairAt = 1001))
        val directory = File(file.parentFile, ".inventory.bin.stage-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        File(directory, "inventory.bin.previous").writeBytes(previous)
        File(directory, "rollback").writeText("present")
        val reopened = GuardInventorySnapshotStore(file, cipher)
        assertEquals(1000, reopened.read().lastAuthoritativeFullRepairAt)
        assertEquals(1000, reopened.read().lastAuthoritativeFullRepairAt)
        assertFalse(directory.exists())
    }

    @Test fun explicitResetDiscardsInterruptedRollbackWithoutResurrectingPreviousData() {
        val file = File(temporary.newFolder(), "inventory.bin")
        val cipher = OwnedCipher()
        val store = GuardInventorySnapshotStore(file, cipher)
        store.write(GuardInventorySnapshot(lastAuthoritativeFullRepairAt = 1000))
        val directory = File(file.parentFile, ".inventory.bin.stage-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        File(directory, "inventory.bin.previous").writeBytes(file.readBytes())
        File(directory, "rollback").writeText("present")
        store.delete()
        assertEquals(GuardInventorySnapshot(), GuardInventorySnapshotStore(file, cipher).read())
        assertFalse(directory.exists())
        assertFalse(file.exists())
    }

    @Test fun healthySuccessiveActualHeartbeatTicksEnumerateOnceWithinSixHours() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.runGuardHeartbeatTick()
            f.clock.now += GuardSentinel.HEARTBEAT_INTERVAL_MS
            f.sentinel.runGuardHeartbeatTick()
            assertEquals(1, f.enumerations)
            assertEquals(1000, f.store.read().lastAuthoritativeFullRepairAt)
        } finally { f.close() }
    }

    @Test fun safetyDeadlineAndClockRollbackAreDueAndSurviveStoreReopen() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.reconcileInventory()
            assertEquals(1, f.enumerations)
            f.clock.now += GuardSentinel.FULL_REPAIR_INTERVAL_MS - 1
            f.sentinel.reconcileInventory(force = false)
            assertEquals(1, f.enumerations)
            f.clock.now++
            f.sentinel.reconcileInventory(force = false)
            assertEquals(2, f.enumerations)
            assertEquals(
                f.clock.now,
                GuardInventorySnapshotStore(f.file, f.cipher).read().lastAuthoritativeFullRepairAt
            )
            f.clock.now = 100
            f.sentinel.reconcileInventory(force = false)
            assertEquals(3, f.enumerations)
        } finally { f.close() }
    }

    @Test fun deltaCaptureDoesNotPostponeAuthoritativeSafetyRepair() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.reconcileInventory()
            f.clock.now += GuardSentinel.FULL_REPAIR_INTERVAL_MS
            f.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REPLACED, f.app.packageName)
            f.sentinel.drainPackageChanges()
            assertEquals(f.clock.now, f.store.read().capturedAt)
            assertEquals(1000, f.store.read().lastAuthoritativeFullRepairAt)
            f.sentinel.reconcileInventory(force = false)
            assertEquals(2, f.enumerations)
        } finally { f.close() }
    }

    @Test fun partialUnresolvedAndMalformedRepairNeverAdvanceFullStampOrClearPending() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.reconcileInventory()
            f.clock.now++
            f.view = GuardPackageVisibleInventory(listOf(f.app), setOf("owned.unresolved"), false)
            f.sentinel.reconcileInventory()
            assertEquals(1000, f.store.read().lastAuthoritativeFullRepairAt)
            assertTrue(f.store.read().fullRepairPending)
            f.sentinel.reconcileInventory(force = false)
            assertEquals(3, f.enumerations)
            f.view = GuardPackageVisibleInventory(listOf(f.app, f.app), emptySet(), true)
            assertTrue(runCatching { f.sentinel.reconcileInventory(force = false) }.isFailure)
            assertEquals(1000, f.store.read().lastAuthoritativeFullRepairAt)
            assertTrue(f.store.read().fullRepairPending)
        } finally { f.close() }
    }

    @Test fun queueOverflowRepairsImmediatelyAndOrdinaryHeartbeatDoesNotRepeatIt() = runBlocking {
        val f = fixture(capacity = 1)
        try {
            f.sentinel.reconcileInventory()
            f.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, "owned.first")
            f.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, "owned.dropped")
            f.sentinel.runGuardHeartbeatTick()
            assertEquals(2, f.enumerations)
            assertEquals(0, f.work.pendingCount())
            assertTrue(f.events.any { it.type == GuardEventType.PACKAGE_EVENT_GAP })
        } finally { f.close() }
    }

    @Test fun failedMetadataForcesRepairEvenWithFreshAuthoritativeStamp() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.reconcileInventory()
            f.descriptionFailure = java.io.IOException("owned missing metadata")
            f.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REPLACED, f.app.packageName)
            f.sentinel.drainPackageChanges()
            assertEquals(2, f.enumerations)
            assertEquals(0, f.work.pendingCount())
        } finally { f.close() }
    }

    @Test fun activationAndExplicitManualRepairBypassHealthyCadence() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.refreshInventoryBaselineForActivation()
            assertEquals(f.clock.now, f.store.read().lastAuthoritativeFullRepairAt)
            f.sentinel.reconcileInventory(force = false)
            assertEquals(1, f.enumerations)
            f.sentinel.reconcileInventory()
            assertEquals(2, f.enumerations)
        } finally { f.close() }
    }

    @Test fun cancelledEnumerationPreservesStampAndDurablePendingRepair() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.reconcileInventory()
            f.clock.now++
            f.snapshotFailure = CancellationException("owned cancelled enumeration")
            assertTrue(runCatching { f.sentinel.reconcileInventory() }.exceptionOrNull() is CancellationException)
            assertEquals(1000, GuardInventorySnapshotStore(f.file, f.cipher).read().lastAuthoritativeFullRepairAt)
            assertTrue(f.store.read().fullRepairPending)
        } finally { f.close() }
    }

    @Test fun cancelledAuthoritativeCipherStagingCannotPublishNewStamp() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.reconcileInventory()
            f.clock.now++
            f.cipher.pauseNextAuthoritative = true
            val job = launch(Dispatchers.IO) { f.sentinel.reconcileInventory() }
            assertTrue(f.cipher.entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            f.cipher.release.countDown()
            job.join()
            assertEquals(1000, GuardInventorySnapshotStore(f.file, f.cipher).read().lastAuthoritativeFullRepairAt)
            assertTrue(f.store.read().fullRepairPending)
        } finally {
            f.cipher.release.countDown()
            f.close()
        }
    }

    @Test fun failedAuthoritativeCommitAndMalformedStoreKeepSafetyDue() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.reconcileInventory()
            f.clock.now++
            f.cipher.failNextAuthoritative = true
            assertTrue(runCatching { f.sentinel.reconcileInventory() }.isFailure)
            assertEquals(1000, f.store.read().lastAuthoritativeFullRepairAt)
            assertTrue(f.store.read().fullRepairPending)
            f.file.writeText("{\"lastAuthoritativeFullRepairAt\":\"broken\"}")
            assertTrue(runCatching { f.sentinel.reconcileInventory(force = false) }.isFailure)
        } finally { f.close() }
    }

    @Test fun epochResetDuringAuthoritativeStagingRejectsStampWithoutWaitingForDisk() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.reconcileInventory()
            f.clock.now++
            f.cipher.pauseNextAuthoritative = true
            val job = launch(Dispatchers.IO) { runCatching { f.sentinel.reconcileInventory() } }
            assertTrue(f.cipher.entered.await(5, TimeUnit.SECONDS))
            f.sentinel.invalidateQueueEpoch()
            f.cipher.release.countDown()
            job.join()
            assertEquals(0, f.store.read().lastAuthoritativeFullRepairAt)
            f.sentinel.settleQueueEpochInvalidation()
            assertFalse(f.file.exists())
        } finally {
            f.cipher.release.countDown()
            f.close()
        }
    }

    @Test fun legacyDeltaTimestampDoesNotSatisfyAuthoritativeRepairStamp() = runBlocking {
        val f = fixture()
        try {
            f.file.writeText("{\"capturedAt\":9000,\"apps\":[{\"packageName\":\"owned.app\"}]}")
            assertEquals(0, f.store.read().lastAuthoritativeFullRepairAt)
            f.sentinel.reconcileInventory(force = false)
            assertEquals(1, f.enumerations)
            assertEquals(1000, f.store.read().lastAuthoritativeFullRepairAt)
        } finally { f.close() }
    }

    @Test fun healthyHeartbeatStillPublishesBothLivenessEvents() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.runGuardHeartbeatTick()
            f.clock.now += GuardSentinel.HEARTBEAT_INTERVAL_MS
            f.sentinel.runGuardHeartbeatTick()
            assertTrue(f.heartbeats.await(5, TimeUnit.SECONDS))
            assertEquals(2, f.events.count { it.type == GuardEventType.HEARTBEAT })
            assertEquals(1, f.enumerations)
        } finally { f.close() }
    }

    @Test fun inactiveRepairPerformsNoInventoryEnumerationOrSnapshotWrite() = runBlocking {
        val f = fixture()
        try {
            f.active = false
            f.sentinel.reconcileInventory(force = false)
            f.sentinel.reconcileInventory()
            assertEquals(0, f.enumerations)
            assertFalse(f.file.exists())
        } finally { f.close() }
    }

    @Test fun rejectedOwnerAfterAdoptionRestoresPreviousDurableStamp() = runBlocking {
        val f = fixture()
        try {
            f.sentinel.reconcileInventory()
            var checks = 0
            val accepted = f.store.writeIfCurrent(
                GuardInventorySnapshot(1001, listOf(f.app), lastAuthoritativeFullRepairAt = 1001)
            ) {
                checks++
                checks < 3
            }
            assertFalse(accepted)
            assertEquals(1000, GuardInventorySnapshotStore(f.file, f.cipher).read().lastAuthoritativeFullRepairAt)
        } finally { f.close() }
    }

    private fun fixture(capacity: Int = 256) = Fixture(temporary.newFolder(), capacity)
    private class Fixture(root: File, capacity: Int) {
        val file = File(root, "inventory.bin")
        val cipher = OwnedCipher()
        val store = GuardInventorySnapshotStore(file, cipher)
        val clock = OwnedClock()
        val app = GuardInventoryApp("owned.app", 1, 1001, "a".repeat(64), 1, 2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = java.util.concurrent.CopyOnWriteArrayList<GuardEvent>()
        val heartbeats = CountDownLatch(2)
        val work = store.packageWorkStore(capacity)
        var active = true
        var enumerations = 0
        var view = GuardPackageVisibleInventory(listOf(app), emptySet(), true)
        var descriptionFailure: Exception? = null
        var snapshotFailure: Exception? = null
        val sentinel = GuardSentinel(
            context = OwnedContext(), clock = clock, inventoryStore = store,
            journal = {
                events.add(it)
                if (it.type == GuardEventType.HEARTBEAT) heartbeats.countDown()
                true
            }, isActive = { active }, lastJournalRecordAt = { 1000 },
            inventoryViewForTest = {
                enumerations++
                snapshotFailure?.let { throw it }
                Result.success(view)
            },
            packageDescriptionForTest = {
                descriptionFailure?.let { throw it }
                app
            },
            packageWakeForTest = {}, packageWorkStoreForTest = work, workScope = scope,
        )
        fun close() {
            sentinel.clearHosts()
            scope.cancel()
        }
    }
    private class OwnedContext : ContextWrapper(null) { override fun getApplicationContext(): Context = this }
    private class OwnedClock : GuardClock {
        var now = 1000L
        override fun wallClockMs(): Long = now
        override fun elapsedRealtimeMs(): Long = now
        override fun bootCount(): Int = 1
    }
    private class OwnedCipher : FileCipher {
        var pauseNextAuthoritative = false
        var failNextAuthoritative = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun readBytes(file: File): ByteArray = file.readBytes()
        override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(plaintext.decodeToString()).toString()
            val full = file.name == "inventory.bin" && json.contains("\"lastAuthoritativeFullRepairAt\":1001")
            if (full && pauseNextAuthoritative) {
                pauseNextAuthoritative = false
                entered.countDown()
                check(
                    release.await(5, TimeUnit.SECONDS)
                )
            }
            if (full && failNextAuthoritative) {
                failNextAuthoritative = false
                error(
                    "owned authoritative IO failure"
                )
            }
            file.writeBytes(plaintext)
        }
    }
}

private class OwnedInventoryCancellation(val marker: Any = Any()) : CancellationException(
    "owned inventory cancellation"
)

private class OwnedInventoryFatal(val marker: Any = Any()) : Error("owned inventory fatal")
