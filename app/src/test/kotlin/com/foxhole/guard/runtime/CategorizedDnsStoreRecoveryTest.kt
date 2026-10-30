package com.foxhole.guard.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CategorizedDnsStoreRecoveryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun ordinaryIoFailureAfterActiveWriteRestoresActivePendingAndReopens() = runBlocking {
        val root = temporary.newFolder()
        val io = FaultIo()
        val store = CategorizedDnsStagedStore(root, io)
        val old = store.stage(bundle())
        store.promote(old) { true }
        val next = store.stage(bundle(2uL))
        io.failPendingRemove = true
        assertEquals(CategorizedDnsPromotion.PENDING_IO, store.promote(next) { true })
        val reopened = CategorizedDnsStagedStore(root)
        assertEquals(old, reopened.activeOrNull("normal-medium"))
        assertEquals(next, reopened.pendingOrNull("normal-medium"))
        assertFalse(File(next.directory.parentFile, "promotion").exists())
    }

    @Test
    fun cancellationPrimarySurvivesRollbackIoFailureAndJournalRecovers() = runBlocking {
        val root = temporary.newFolder()
        val io = FaultIo()
        val store = CategorizedDnsStagedStore(root, io)
        val old = store.stage(bundle())
        store.promote(old) { true }
        val next = store.stage(bundle(2uL))
        val cancelled = OwnedDnsPromotionCancellation()
        var checks = 0
        val caught = assertThrows(CancellationException::class.java) {
            runBlocking {
                store.promote(next) {
                    if (++checks == 3) {
                        io.failActiveReplace = true
                        throw cancelled
                    }
                    true
                }
            }
        }
        assertSame(cancelled, caught)
        assertTrue(caught.suppressed.single() is IOException)
        assertTrue(File(next.directory.parentFile, "promotion").isFile)
        val reopened = CategorizedDnsStagedStore(root)
        assertEquals(old, reopened.activeOrNull("normal-medium"))
        assertEquals(next, reopened.pendingOrNull("normal-medium"))
    }

    @Test
    fun fatalOwnerFailurePropagatesAndRestoresPreviousState() = runBlocking {
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val old = store.stage(bundle())
        store.promote(old) { true }
        val next = store.stage(bundle(2uL))
        val fatal = OwnedDnsPromotionFatal()
        var checks = 0
        assertSame(
            fatal,
            assertThrows(LinkageError::class.java) {
                runBlocking { store.promote(next) { if (++checks == 3) throw fatal else true } }
            }
        )
        assertEquals(old, store.activeOrNull("normal-medium"))
        assertEquals(next, store.pendingOrNull("normal-medium"))
    }

    @Test
    fun snapshotIsIndependentAfterUnreferencedDirectoryRetires() = runBlocking {
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val old = store.stage(bundle())
        store.promote(old) { true }
        val captured = store.readBundle(old)
        store.promote(store.stage(bundle(2uL))) { true }
        store.stage(bundle(3uL))
        assertFalse(old.directory.exists())
        assertArrayEquals(bundle().artifactBytes, captured.artifact)
        assertTrue(captured.manifest.isNotEmpty())
    }

    @Test
    fun oneOpaqueReaderPinsDirectoryUntilAllThreeFileReadsSettle() = runBlocking {
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val old = store.stage(bundle())
        store.promote(old) { true }
        val lease = requireNotNull(store.acquireReadLease(old))
        assertNull(store.acquireReadLease(old))
        val firstRead = CompletableDeferred<Unit>()
        val allowRest = CompletableDeferred<Unit>()
        val reading = async(Dispatchers.IO) {
            val manifest = File(old.directory, "manifest.json").readBytes()
            firstRead.complete(Unit)
            allowRest.await()
            val signature = File(old.directory, "manifest.json.sig").readBytes()
            val artifact = File(old.directory, "dns-categories.fhds").readBytes()
            Triple(manifest, signature, artifact)
        }
        firstRead.await()
        store.promote(store.stage(bundle(2uL))) { true }
        store.promote(store.stage(bundle(3uL))) { true }
        assertTrue(old.directory.isDirectory)
        allowRest.complete(Unit)
        assertArrayEquals(bundle().artifactBytes, reading.await().third)
        lease.release()
        lease.release()
        assertFalse(old.directory.exists())
    }

    @Test
    fun retirementNeverDeletesUnownedCallerDirectory() = runBlocking {
        val root = temporary.newFolder()
        val store = CategorizedDnsStagedStore(root)
        val first = store.stage(bundle())
        store.promote(first) { true }
        val caller = File(first.directory.parentFile, "12345678-1234-1234-1234-123456789012")
        assertTrue(caller.mkdir())
        File(caller, "sentinel").writeText("caller")
        store.promote(store.stage(bundle(2uL))) { true }
        store.stage(bundle(3uL))
        assertEquals("caller", File(caller, "sentinel").readText())
    }

    @Test
    fun fullSnapshotRejectsTamperedFileEvenWhenCheapStateReadSucceeds() = runBlocking {
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val stage = store.stage(bundle())
        File(stage.directory, "manifest.json").writeText("tampered")
        assertEquals(stage, store.pendingOrNull("normal-medium"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.readBundle(stage) } }
        Unit
    }

    private class FaultIo : CategorizedDnsStoreIo {
        var failPendingRemove = false
        var failActiveReplace = false

        override fun replace(parent: File, name: String, bytes: ByteArray) {
            if (name == "active" && failActiveReplace) throw IOException("owned rollback fault")
            FileCategorizedDnsStoreIo.replace(parent, name, bytes)
        }

        override fun remove(file: File): Boolean {
            if (file.name == "pending" && failPendingRemove) {
                failPendingRemove = false
                throw IOException("owned pending removal fault")
            }
            return FileCategorizedDnsStoreIo.remove(file)
        }
    }
}

private class OwnedDnsPromotionCancellation(val marker: Any = Any()) : CancellationException("owned primary")
private class OwnedDnsPromotionFatal(val marker: Any = Any()) : LinkageError("owned fatal")
