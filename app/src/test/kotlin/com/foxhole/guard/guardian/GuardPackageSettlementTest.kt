package com.foxhole.guard.guardian

import android.content.Context
import android.content.ContextWrapper
import com.foxhole.guard.core.security.FakeGuardCrypto
import com.foxhole.guard.core.sentinel.FileCipher
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicReference

class GuardPackageSettlementTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `transient add remove remains ordered while final removal needs no heavy metadata or full scan`() = runBlocking {
        val fixture = fixture()
        val name = "owned.transient"
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_ADDED, name)
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, name)
        fixture.sentinel.drainPackageChanges()
        assertEquals(
            listOf(GuardEventType.PACKAGE_ADDED, GuardEventType.PACKAGE_REMOVED),
            fixture.events.map { it.type }
        )
        assertTrue(fixture.events.first().detail.orEmpty().contains("metadataUnavailable=true"))
        assertEquals(0, fixture.queries)
        assertEquals(0, fixture.scans)
        assertEquals(1, fixture.wakes)
        assertEquals(0, fixture.work.pendingCount())
        assertFalse(fixture.inventory.read().apps.any { it.packageName == name })
    }

    @Test
    fun `repeated update edges coalesce current facts while retaining every admitted observation`() = runBlocking {
        val fixture = fixture()
        repeat(3) { fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REPLACED, fixture.live.packageName) }
        fixture.sentinel.drainPackageChanges()
        assertEquals(3, fixture.events.size)
        assertEquals(1, fixture.queries)
        assertEquals(0, fixture.scans)
        assertTrue(fixture.events.take(2).all { it.detail.orEmpty().contains("metadataUnavailable=true") })
        assertEquals(fixture.live.signerSha256, fixture.events.last().signerSha256)
        assertEquals(listOf(fixture.live), fixture.inventory.read().apps)
    }

    @Test
    fun `journal failure preserves claim and baseline then retry settles it`() = runBlocking {
        val fixture = fixture()
        fixture.journalReady = false
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, fixture.live.packageName)
        assertTrue(runCatching { fixture.sentinel.drainPackageChanges() }.isFailure)
        assertEquals(1, fixture.work.pendingCount())
        assertEquals(10L, fixture.inventory.read().capturedAt)
        fixture.journalReady = true
        fixture.sentinel.drainPackageChanges()
        assertEquals(0, fixture.work.pendingCount())
        assertEquals(1, fixture.events.size)
    }

    @Test
    fun `baseline failure after journal ACK replays at least once after process store reopen`() = runBlocking {
        val fixture = fixture()
        fixture.cipher.failInventory = true
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, fixture.live.packageName)
        assertTrue(runCatching { fixture.sentinel.drainPackageChanges() }.isFailure)
        assertEquals(1, fixture.work.pendingCount())
        assertEquals(1, fixture.events.size)
        val restarted = GuardPackageWorkStore(fixture.workFile, fixture.cipher)
        val owner = checkNotNull(restarted.beginDrain())
        assertEquals(GuardEventType.PACKAGE_REMOVED, restarted.claim(owner).observations.single().type)
        fixture.cipher.failInventory = false
        fixture.sentinel.drainPackageChanges()
        assertEquals(0, fixture.work.pendingCount())
        assertEquals(2, fixture.events.size)
    }

    @Test
    fun `typed overflow gap carries scalar count and complete repair is required before dequeue`() = runBlocking {
        val fixture = fixture(capacity = 1)
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, "owned.first")
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_ADDED, "owned.dropped")
        fixture.sentinel.drainPackageChanges()
        val gap = fixture.events.single { it.type == GuardEventType.PACKAGE_EVENT_GAP }
        assertEquals("package-worker", gap.service)
        assertEquals("dropped=1;fullRepairRequired=true", gap.detail)
        assertEquals(null, gap.packageName)
        assertEquals(1, fixture.scans)
        assertEquals(0, fixture.work.pendingCount())
    }

    @Test
    fun `partial visibility cannot settle gap or infer missing baseline removal`() = runBlocking {
        val fixture = fixture(capacity = 1)
        fixture.view = GuardPackageVisibleInventory(listOf(complete("owned.visible")), emptySet(), false)
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, "owned.first")
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_ADDED, "owned.dropped")
        assertTrue(runCatching { fixture.sentinel.drainPackageChanges() }.isFailure)
        assertEquals(1, fixture.work.pendingCount())
        assertEquals(10L, fixture.inventory.read().capturedAt)
        assertTrue(fixture.inventory.read().apps.contains(fixture.live))
        val owner = checkNotNull(fixture.work.beginDrain())
        assertTrue(fixture.work.claim(owner).fullRepairRequired)
        fixture.work.finishDrain(owner, reserveSuccessor = false)
        Unit
    }

    @Test
    fun `partial signer inventory preserves old facts and complete baseline timestamp`() = runBlocking {
        val fixture = fixture()
        fixture.view = GuardPackageVisibleInventory(listOf(complete("owned.visible")), setOf(fixture.live.packageName), false)
        fixture.sentinel.reconcileInventory()
        assertEquals(10L, fixture.inventory.read().capturedAt)
        assertTrue(fixture.inventory.read().apps.contains(fixture.live))
        assertFalse(fixture.events.any { it.type == GuardEventType.PACKAGE_REMOVED })
    }

    @Test
    fun `unresolved current metadata stays pending when repair has limited visibility`() = runBlocking {
        val fixture = fixture()
        fixture.descriptionFailure = GuardPackageMetadataUnavailableException()
        fixture.view = GuardPackageVisibleInventory(listOf(complete("owned.visible")), setOf(fixture.live.packageName), false)
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_ADDED, fixture.live.packageName)
        assertTrue(runCatching { fixture.sentinel.drainPackageChanges() }.isFailure)
        assertEquals(1, fixture.work.pendingCount())
        assertTrue(fixture.inventory.read().apps.contains(fixture.live))
    }

    @Test
    fun `cancel and fatal preserve their identity and never dequeue claimed observations`() = runBlocking {
        listOf(OwnedPackageCancellation(), OwnedPackageFatal()).forEach { error ->
            val fixture = fixture()
            fixture.journalFailure = error
            fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, "owned.remove")
            assertSame(error, runCatching { fixture.sentinel.drainPackageChanges() }.exceptionOrNull())
            assertEquals(1, fixture.work.pendingCount())
        }
    }

    @Test
    fun `bounded drain reserves one successor for remaining work rather than one job per edge`() = runBlocking {
        val fixture = fixture()
        repeat(129) { fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, "owned.remove$it") }
        assertEquals(1, fixture.wakes)
        fixture.sentinel.drainPackageChanges()
        assertEquals(128, fixture.events.size)
        assertEquals(1, fixture.work.pendingCount())
        assertEquals(2, fixture.wakes)
        fixture.sentinel.drainPackageChanges()
        assertEquals(129, fixture.events.size)
        assertEquals(0, fixture.work.pendingCount())
    }

    @Test
    fun `epoch invalidation returns during paused inventory write and prevents stale publication and ACK`() {
        val fixture = fixture()
        runBlocking { fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, fixture.live.packageName) }
        fixture.cipher.pauseInventory = true
        val failure = AtomicReference<Throwable>()
        val drain =
            Thread {
                try { runBlocking { fixture.sentinel.drainPackageChanges() } } catch (error: Throwable) {
                    failure.set(
                        error
                    )
                }
            }
        val invalidated = CountDownLatch(1)
        val invalidate = Thread {
            fixture.sentinel.invalidateQueueEpoch()
            invalidated.countDown()
        }
        try {
            drain.start()
            assertTrue(fixture.cipher.entered.await(5, TimeUnit.SECONDS))
            invalidate.start()
            assertTrue("logical invalidation cannot wait on disk IO", invalidated.await(5, TimeUnit.SECONDS))
            fixture.cipher.release.countDown()
            drain.join(5000)
            assertFalse(drain.isAlive)
            assertTrue(failure.get() is IllegalStateException)
            assertEquals(0L, fixture.inventory.read().capturedAt)
            assertEquals(0, fixture.work.pendingCount())
            runBlocking { fixture.sentinel.settleQueueEpochInvalidation() }
            assertFalse(fixture.inventoryFile.exists())
            assertFalse(fixture.workFile.exists())
        } finally {
            fixture.cipher.release.countDown()
            drain.join(5000)
            invalidate.join(5000)
        }
    }

    @Test
    fun `inactive admission and drain perform no PM query journal or durable write`() = runBlocking {
        val fixture = fixture()
        fixture.active = false
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_ADDED, fixture.live.packageName)
        fixture.sentinel.drainPackageChanges()
        assertFalse(fixture.workFile.exists())
        assertEquals(0, fixture.queries)
        assertEquals(0, fixture.events.size)
    }

    @Test
    fun `failed scheduler retains admitted work and recovery reserves another wake`() = runBlocking {
        val fixture = fixture()
        val failed = OwnedPackageSchedulerFailure()
        fixture.wakeFailure = failed
        assertSame(
            failed,
            runCatching {
                fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, fixture.live.packageName)
            }.exceptionOrNull()
        )
        assertEquals(1, fixture.work.pendingCount())
        fixture.wakeFailure = null
        fixture.sentinel.wakePendingPackageChanges()
        assertEquals(2, fixture.wakes)
        fixture.sentinel.drainPackageChanges()
        assertEquals(0, fixture.work.pendingCount())
    }

    @Test
    fun `failed direct metadata settles only when complete full repair provides current facts or scoped absence`() = runBlocking {
        val fixture = fixture()
        fixture.descriptionFailure = GuardPackageMetadataUnavailableException()
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_ADDED, fixture.live.packageName)
        fixture.sentinel.drainPackageChanges()
        assertEquals(0, fixture.work.pendingCount())
        assertEquals(listOf(fixture.live), fixture.inventory.read().apps)
        assertTrue(fixture.events.single().detail.orEmpty().contains("metadataUnavailable=true"))
        fixture.view = GuardPackageVisibleInventory(listOf(complete("owned.retained")), emptySet(), true)
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_ADDED, "owned.alreadyGone")
        fixture.sentinel.drainPackageChanges()
        assertEquals(0, fixture.work.pendingCount())
        assertFalse(fixture.inventory.read().apps.any { it.packageName == "owned.alreadyGone" })
    }

    @Test
    fun `failed baseline cleanup keeps stale disk facts hidden`() {
        val file = object : File(temporary.newFolder(), "owned-refused-inventory.bin") {
            override fun delete(): Boolean = false
        }
        val store = GuardInventorySnapshotStore(file, OwnedCipher())
        store.write(GuardInventorySnapshot(10, listOf(complete("owned.stale"))))
        store.invalidateEpoch()
        assertTrue(runCatching { store.settleInvalidation() }.isFailure)
        assertEquals(GuardInventorySnapshot(), store.read())
    }

    @Test
    fun `journal clear preserves admitted removal reinstall edges and trusted baseline while suppressing old journal effects`() = runBlocking {
        val fixture = fixture()
        fixture.described = fixture.live.copy(firstInstallTime = 3L, lastUpdateTime = 4L)
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, fixture.live.packageName)
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_ADDED, fixture.live.packageName)
        fixture.sentinel.invalidatePackageJournalEpoch()
        fixture.sentinel.settlePackageJournalEpochInvalidation()
        assertEquals(2, fixture.work.pendingCount())
        assertEquals(GuardInventorySnapshot(10, listOf(fixture.live)), fixture.inventory.read())
        fixture.sentinel.drainPackageChanges()
        assertTrue(fixture.events.isEmpty())
        assertEquals(listOf(fixture.described), fixture.inventory.read().apps)
        assertEquals(0, fixture.work.pendingCount())
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REPLACED, fixture.live.packageName)
        fixture.sentinel.drainPackageChanges()
        assertEquals(listOf(GuardEventType.PACKAGE_REPLACED), fixture.events.map { it.type })
    }

    @Test
    fun `durable journal fence survives fresh process store while queued edges still settle baseline`() = runBlocking {
        val fixture = fixture()
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, fixture.live.packageName)
        fixture.sentinel.invalidatePackageJournalEpoch()
        fixture.sentinel.settlePackageJournalEpochInvalidation()
        val restartedWork = GuardPackageWorkStore(fixture.workFile, fixture.cipher)
        val restartedInventory = GuardInventorySnapshotStore(fixture.inventoryFile, fixture.cipher)
        val events = mutableListOf<GuardEvent>()
        val restarted = GuardSentinel(
            context = OwnedContext(),
            clock = OwnedClock,
            inventoryStore = restartedInventory,
            journal = {
                events += it
                true
            },
            isActive = { true },
            lastJournalRecordAt = { null },
            packageWorkStoreForTest = restartedWork,
            packageWakeForTest = {},
        )
        restarted.drainPackageChanges()
        assertTrue(events.isEmpty())
        assertTrue(restartedInventory.read().apps.isEmpty())
        assertEquals(0, restartedWork.pendingCount())
    }

    @Test
    fun `actual journal locked predicate refusal after clear suppresses journal effect without retrying baseline settlement`() = runBlocking {
        val fixture = fixture()
        val crypto = FakeGuardCrypto()
        val keys = crypto.sealedBoxKeypair()
        val directory = temporary.newFolder()
        val journal = GuardJournal(directory, crypto, OwnedClock, { keys.publicKey }, { -1L })
        fixture.guardedJournal = { event, current ->
            fixture.sentinel.invalidatePackageJournalEpoch()
            journal.appendIfCurrent(event, current)
        }
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, fixture.live.packageName)
        fixture.sentinel.drainPackageChanges()
        assertEquals(0, fixture.work.pendingCount())
        assertTrue(fixture.inventory.read().apps.isEmpty())
        assertEquals(null, journal.lastRecordWallClockMs())
    }

    @Test
    fun `overflow before clear still requires full repair but only post clear dropped count is journaled`() = runBlocking {
        val fixture = fixture(capacity = 1)
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, "owned.admitted")
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_ADDED, "owned.oldGap")
        fixture.sentinel.invalidatePackageJournalEpoch()
        fixture.sentinel.settlePackageJournalEpochInvalidation()
        fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_ADDED, "owned.newGap")
        fixture.sentinel.drainPackageChanges()
        assertEquals(1, fixture.scans)
        assertEquals(0, fixture.work.pendingCount())
        val gap = fixture.events.single()
        assertEquals(GuardEventType.PACKAGE_EVENT_GAP, gap.type)
        assertEquals("dropped=1;fullRepairRequired=true", gap.detail)
    }

    @Test
    fun `clear before queue load retains durable observations and changes only their journal eligibility`() {
        val fixture = fixture()
        runBlocking { fixture.sentinel.enqueuePackageChange(GuardEventType.PACKAGE_REMOVED, fixture.live.packageName) }
        val restarted = GuardPackageWorkStore(fixture.workFile, fixture.cipher)
        restarted.invalidateJournalEpoch()
        restarted.settleJournalInvalidation()
        assertEquals(1, restarted.pendingCount())
        val owner = checkNotNull(restarted.beginDrain())
        val claim = restarted.claim(owner)
        assertFalse(restarted.isJournalCurrent(claim.observations.single().journalEpoch))
        assertTrue(restarted.isCurrent(claim.epoch))
        assertTrue(restarted.acknowledge(claim, true))
    }

    private fun fixture(capacity: Int = 256): Fixture = Fixture(temporary.newFolder(), capacity)

    private class Fixture(root: File, capacity: Int) {
        val inventoryFile = File(root, "inventory.bin")
        val workFile = File(root, "package-work.json")
        val cipher = OwnedCipher()
        val inventory = GuardInventorySnapshotStore(inventoryFile, cipher)
        val work by lazy { inventory.packageWorkStore(capacity) }
        val live = complete("owned.live")
        var described = live
        val events = mutableListOf<GuardEvent>()
        var active = true
        var journalReady = true
        var journalFailure: Throwable? = null
        var guardedJournal: ((GuardEvent, () -> Boolean) -> Boolean)? = null
        var descriptionFailure: Exception? = null
        var queries = 0
        var scans = 0
        var wakes = 0
        var wakeFailure: Exception? = null
        var view = GuardPackageVisibleInventory(listOf(live), emptySet(), true)
        val sentinel: GuardSentinel
        init {
            inventory.write(GuardInventorySnapshot(10, listOf(live)))
            sentinel = GuardSentinel(
                context = OwnedContext(), clock = OwnedClock, inventoryStore = inventory,
                journal = { event ->
                    journalFailure?.let { throw it }
                    if (journalReady) events += event
                    journalReady
                },
                journalIfCurrent = { event, current ->
                    guardedJournal?.invoke(event, current) ?: (
                        current() && run {
                            journalFailure?.let { throw it }
                            if (journalReady) events += event
                            journalReady
                        }
                        )
                },
                isActive = { active }, lastJournalRecordAt = { null },
                packageDescriptionForTest = {
                    queries++
                    descriptionFailure?.let { throw it }
                    described
                },
                inventoryViewForTest = {
                    scans++
                    Result.success(view)
                },
                packageWakeForTest = {
                    wakes++
                    wakeFailure?.let { throw it }
                }, packageWorkStoreForTest = work,
            )
        }
    }

    private class OwnedCipher : FileCipher {
        var failInventory = false
        var pauseInventory = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun readBytes(file: File): ByteArray = file.readBytes()
        override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
            if (file.name == "inventory.bin") {
                if (failInventory) error("owned inventory failure")
                if (pauseInventory) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            file.writeBytes(plaintext)
        }
    }
    private class OwnedContext : ContextWrapper(null) { override fun getApplicationContext(): Context = this }
    private object OwnedClock : GuardClock {
        override fun wallClockMs(): Long = 100
        override fun elapsedRealtimeMs(): Long = 100
        override fun bootCount(): Int = 1
    }
    private companion object {
        fun complete(name: String) = GuardInventoryApp(name, 1L, 41, "a".repeat(64), 1L, 2L)
    }
}

private class OwnedPackageCancellation(val marker: Any = Any()) : CancellationException("owned cancellation")
private class OwnedPackageFatal(val marker: Any = Any()) : Error("owned fatal")
private class OwnedPackageSchedulerFailure(val marker: Any = Any()) : IllegalStateException(
    "owned scheduler unavailable"
)
