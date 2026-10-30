package com.foxhole.guard.guardian

import android.content.Context
import android.content.ContextWrapper
import com.foxhole.guard.core.security.FakeGuardCrypto
import com.foxhole.guard.core.sentinel.FileCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GuardSentinelIoOwnershipTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `same host restart waits for old terminal and final detach survives caller cancellation`() {
        val root = temporary.newFolder("restart-journal")
        val crypto = FakeGuardCrypto()
        val keys = crypto.sealedBoxKeypair()
        val journal = GuardJournal(File(root, "journal"), crypto, FakeGuardClock(), { keys.publicKey }, { -1L })
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val finalWritten = CountDownLatch(1)
        val oldTerminalEntered = CountDownLatch(1)
        val releaseOldTerminal = CountDownLatch(1)
        val finalTerminalEntered = CountDownLatch(1)
        val releaseFinalTerminal = CountDownLatch(1)
        val starts = AtomicInteger()
        val terminals = AtomicInteger()
        val sentinel = sentinel(root, processScope, journal = { true }, guardedJournal = { event, current ->
            if (event.type == GuardEventType.SERVICE_DESTROYED) {
                if (terminals.incrementAndGet() == 1) {
                    oldTerminalEntered.countDown()
                    await(releaseOldTerminal)
                } else {
                    finalTerminalEntered.countDown()
                    await(releaseFinalTerminal)
                }
            }
            journal.appendIfCurrent(event, current).also { recorded ->
                if (recorded && event.type == GuardEventType.SERVICE_STARTED) {
                    if (starts.incrementAndGet() == 1) firstStarted.countDown() else secondStarted.countDown()
                }
                if (recorded && event.type == GuardEventType.SERVICE_DESTROYED && terminals.get() == 2) finalWritten.countDown()
            }
        })
        try {
            sentinel.attach("host")
            sentinel.attach("host")
            await(firstStarted)
            sentinel.detach("host", destroyed = true)
            sentinel.detach("host", destroyed = true)
            await(oldTerminalEntered)
            sentinel.attach("host")
            assertEquals(1, starts.get())
            assertTrue(sentinel.lifecycleIoHealth().rejected > 0)
            releaseOldTerminal.countDown()
            await(secondStarted)
            callerScope.launch { sentinel.detach("host", destroyed = true) }
            await(finalTerminalEntered)
            callerScope.cancel()
            releaseFinalTerminal.countDown()
            await(finalWritten)
            assertFalse(sentinel.hasAttachedHosts())
            val report = GuardJournalVerifier(
                File(root, "journal"),
                crypto
            ).verify(keys.publicKey, keys.privateKey, null)
            assertEquals(GuardJournalStatus.OK, report.status)
            assertEquals(
                listOf(
                    GuardEventType.SERVICE_STARTED,
                    GuardEventType.SERVICE_DESTROYED,
                    GuardEventType.SERVICE_STARTED,
                    GuardEventType.SERVICE_DESTROYED
                ),
                report.entries.mapNotNull { it.event?.type }.filter { it != GuardEventType.GENESIS && it != GuardEventType.HEARTBEAT },
            )
            assertEquals(2, starts.get())
            assertEquals(2, terminals.get())
        } finally {
            releaseOldTerminal.countDown()
            releaseFinalTerminal.countDown()
            sentinel.clearHosts()
            callerScope.cancel()
            processScope.cancel()
        }
    }

    @Test
    fun `reconciliation and durable package settlement intrinsically execute on IO`() = runBlocking {
        val root = temporary.newFolder("inventory-io")
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val uiThread = withContext(ui) { Thread.currentThread() }
        val observed = Collections.synchronizedList(mutableListOf<Thread>())
        val store = store(root) { observed += Thread.currentThread() }
        val app = GuardInventoryApp("owned.app", versionCode = 1)
        store.write(GuardInventorySnapshot(10, listOf(app)))
        observed.clear()
        val sentinel = GuardSentinel(
            context = OwnedContext(),
            clock = FakeGuardClock(),
            inventoryStore = store,
            journal = {
                observed += Thread.currentThread()
                true
            },
            isActive = { true },
            lastJournalRecordAt = { null },
            workScope = processScope,
            packageWakeForTest = {},
            inventorySnapshotForTest = {
                observed += Thread.currentThread()
                Result.success(listOf(app))
            },
        )
        try {
            withContext(ui) {
                sentinel.reconcileInventory()
                sentinel.recordPackageChange(GuardEventType.PACKAGE_REMOVED, app.packageName)
                sentinel.drainPackageChanges()
            }
            assertTrue(observed.size >= 5)
            observed.forEach { assertNotSame(uiThread, it) }
        } finally {
            sentinel.clearHosts()
            processScope.cancel()
            ui.close()
        }
    }

    @Test
    fun `inventory snapshot does not advance when its actual synchronous journal ACK fails`() = runBlocking {
        val root = temporary.newFolder("inventory-ack")
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = store(root)
        val retained = GuardInventoryApp("owned.retained")
        store.write(GuardInventorySnapshot(10, listOf(retained, GuardInventoryApp("owned.removed"))))
        val appended = AtomicReference<GuardEvent>()
        val sentinel = GuardSentinel(
            context = OwnedContext(),
            clock = FakeGuardClock(),
            inventoryStore = store,
            journal = {
                appended.set(it)
                false
            },
            isActive = { true },
            lastJournalRecordAt = { null },
            workScope = processScope,
            inventorySnapshotForTest = { Result.success(listOf(retained)) },
        )
        try {
            val failure = runCatching { sentinel.reconcileInventory() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(GuardEventType.PACKAGE_REMOVED, appended.get()?.type)
            assertEquals(10L, store.read().capturedAt)
            assertEquals(2, store.read().apps.size)
        } finally {
            sentinel.clearHosts()
            processScope.cancel()
        }
    }

    @Test
    fun `failure and capacity callbacks are outside host and journal locks`() {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = temporary.newFolder("callback-locks")
        val crypto = FakeGuardCrypto()
        val keys = crypto.sealedBoxKeypair()
        val journal = GuardJournal(File(root, "journal"), crypto, FakeGuardClock(), { keys.publicKey }, { -1L })
        val failed = CountDownLatch(1)
        val capacity = CountDownLatch(1)
        val lockFailure = AtomicReference<String>()
        lateinit var monitor: GuardHostMonitor
        val lane = GuardOrderedLifecycleIo(processScope, onFailure = {
            if (Thread.holdsLock(monitor) || Thread.holdsLock(journal)) {
                lockFailure.set(
                    "failure callback under owner lock"
                )
            }
            failed.countDown()
        }, onCapacityAvailable = {
            if (Thread.holdsLock(monitor) || Thread.holdsLock(journal)) {
                lockFailure.set(
                    "capacity callback under owner lock"
                )
            }
            capacity.countDown()
        })
        monitor = GuardHostMonitor(
            scope = processScope, monitoringActive = { true },
            onHostStarted = { host ->
                lane.start(host) { current ->
                    journal.appendIfCurrent(GuardEvent(GuardEventType.SERVICE_STARTED), current)
                    false
                } == GuardIoAdmission.ACCEPTED
            },
            onHostStopped = { host, _ -> lane.terminal(host) { true } },
            onHeartbeat = {}, heartbeatIntervalMs = 60_000,
        )
        try {
            monitor.attach("host")
            await(failed)
            await(capacity)
            assertEquals(null, lockFailure.get())
        } finally {
            monitor.clear()
            lane.invalidate()
            processScope.cancel()
        }
    }

    private fun sentinel(
        root: File,
        processScope: CoroutineScope,
        journal: (GuardEvent) -> Boolean,
        guardedJournal: (GuardEvent, () -> Boolean) -> Boolean,
    ) = GuardSentinel(
        context = OwnedContext(), clock = FakeGuardClock(), inventoryStore = store(root),
        journal = journal, isActive = { true }, lastJournalRecordAt = { null }, workScope = processScope,
        inventorySnapshotForTest = { Result.success(listOf(GuardInventoryApp("owned.synthetic.app"))) },
        journalIfCurrent = guardedJournal,
    )

    private fun store(
        root: File,
        written: () -> Unit = {
        }
    ) = GuardInventorySnapshotStore(
        File(root, "inventory.bin"),
        object : FileCipher {
            override fun readBytes(file: File): ByteArray = file.readBytes()
            override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
                file.writeBytes(plaintext)
                written()
            }
        }
    )

    private fun await(latch: CountDownLatch) {
        assertTrue("owned operation completed", latch.await(5, TimeUnit.SECONDS))
    }

    private class OwnedContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
