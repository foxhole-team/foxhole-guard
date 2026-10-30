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
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GuardMainIoProofTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `attach returns while its journal operation is blocked off the callback thread`() {
        lifecycleProof(GuardEventType.SERVICE_STARTED)
    }

    @Test
    fun `final detach returns while its journal operation is blocked off the callback thread`() {
        lifecycleProof(GuardEventType.SERVICE_DESTROYED)
    }

    private fun lifecycleProof(blockedType: GuardEventType) {
        val root = temporary.newFolder("owned-lifecycle")
        val crypto = FakeGuardCrypto()
        val keys = crypto.sealedBoxKeypair()
        val clock = FakeGuardClock()
        val journal = GuardJournal(File(root, "journal"), crypto, clock, { keys.publicKey }, { -1L })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbackReturned = CountDownLatch(1)
        val bothWritten = CountDownLatch(2)
        val writerThread = AtomicReference<Thread>()
        val callbackThread = AtomicReference<Thread>()
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sentinel = sentinel(root, processScope) { event ->
            if (event.type == GuardEventType.HEARTBEAT) {
                true
            } else {
                if (event.type == blockedType) {
                    writerThread.set(Thread.currentThread())
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "owned fixture writer release" }
                }
                clock.advance(1)
                journal.append(event).also { if (it) bothWritten.countDown() }
            }
        }
        val callback = Executors.newSingleThreadExecutor { task -> Thread(task, "owned-service-callback") }
        if (blockedType == GuardEventType.SERVICE_DESTROYED) sentinel.attach("owned-host")
        val result = callback.submit {
            callbackThread.set(Thread.currentThread())
            try {
                if (blockedType == GuardEventType.SERVICE_STARTED) {
                    sentinel.attach("owned-host")
                } else {
                    sentinel.detach("owned-host", destroyed = true)
                }
            } finally {
                callbackReturned.countDown()
            }
        }
        try {
            assertTrue("the injected journal operation was actually entered", entered.await(3, TimeUnit.SECONDS))
            assertTrue("callback must return without releasing the writer", callbackReturned.await(2, TimeUnit.SECONDS))
            assertNotSame(callbackThread.get(), writerThread.get())
        } finally {
            release.countDown()
            result.get(5, TimeUnit.SECONDS)
            if (blockedType == GuardEventType.SERVICE_STARTED) sentinel.detach("owned-host", destroyed = true)
            assertTrue("both required lifecycle records complete", bothWritten.await(3, TimeUnit.SECONDS))
            sentinel.clearHosts()
            processScope.cancel()
            callback.shutdownNow()
        }
        val report = GuardJournalVerifier(
            File(root, "journal"),
            crypto
        ).verify(keys.publicKey, keys.privateKey, checkpoint = null)
        assertEquals(GuardJournalStatus.OK, report.status)
        assertEquals(
            listOf(GuardEventType.GENESIS, GuardEventType.SERVICE_STARTED, GuardEventType.SERVICE_DESTROYED),
            report.entries.map { it.event?.type },
        )
    }

    @Test
    fun `activation suspends its UI owner while inspector and snapshot writer execute on IO`() = runBlocking {
        val root = temporary.newFolder("owned-activation")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uiPing = CountDownLatch(1)
        val inspectorThread = AtomicReference<Thread>()
        val writeThread = AtomicReference<Thread>()
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ui = Executors.newSingleThreadExecutor { task -> Thread(task, "owned-ui-callback") }.asCoroutineDispatcher()
        val uiScope = CoroutineScope(SupervisorJob() + ui)
        val uiThread = withContext(ui) { Thread.currentThread() }
        val inventory = GuardInventorySnapshotStore(
            File(root, "inventory.bin"),
            object : FileCipher {
                override fun readBytes(file: File): ByteArray = file.readBytes().reversedArray()
                override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
                    writeThread.set(Thread.currentThread())
                    file.parentFile?.mkdirs()
                    file.writeBytes(plaintext.reversedArray())
                }
            }
        )
        val sentinel = GuardSentinel(
            context = OwnedContext(),
            clock = FakeGuardClock(),
            inventoryStore = inventory,
            journal = { true },
            isActive = { false },
            lastJournalRecordAt = { null },
            workScope = processScope,
            inventorySnapshotForTest = {
                inspectorThread.set(Thread.currentThread())
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "owned inspector release" }
                Result.success(listOf(GuardInventoryApp("owned.synthetic.app", versionCode = 1L)))
            },
        )
        val activation = uiScope.launch { sentinel.refreshInventoryBaselineForActivation() }
        try {
            assertTrue("actual activation invoked the injected inspector", entered.await(3, TimeUnit.SECONDS))
            uiScope.launch { uiPing.countDown() }
            assertTrue("UI owner remains schedulable while IO is held", uiPing.await(2, TimeUnit.SECONDS))
            assertNotSame(uiThread, inspectorThread.get())
        } finally {
            release.countDown()
            withTimeout(5_000) { activation.join() }
            sentinel.clearHosts()
            uiScope.cancel()
            processScope.cancel()
            ui.close()
        }
        assertNotSame(uiThread, writeThread.get())
        assertEquals(listOf("owned.synthetic.app"), inventory.read().apps.map { it.packageName })
    }

    private fun sentinel(
        root: File,
        processScope: CoroutineScope,
        journal: (GuardEvent) -> Boolean,
    ): GuardSentinel = GuardSentinel(
        context = OwnedContext(),
        clock = FakeGuardClock(),
        inventoryStore = GuardInventorySnapshotStore(
            File(root, "inventory.bin"),
            object : FileCipher {
                override fun readBytes(file: File): ByteArray = file.readBytes()
                override fun writeBytesAtomic(file: File, plaintext: ByteArray) = file.writeBytes(plaintext)
            }
        ),
        journal = journal,
        isActive = { true },
        lastJournalRecordAt = { null },
        workScope = processScope,
        inventorySnapshotForTest = { Result.success(listOf(GuardInventoryApp("owned.synthetic.app"))) },
    )

    private class OwnedContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
