package com.foxhole.guard.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CategorizedDnsColdReadCountTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun coldPinReadsNoPayloadAndLiveSnapshotReadsExactlyThreeFiles() = runBlocking {
        val io = CountIo()
        val store = CategorizedDnsStagedStore(temporary.newFolder(), io)
        val stage = store.stage(bundle())
        val lease = requireNotNull(store.acquireReadLease(stage))
        assertEquals(emptyList<String>(), io.reads)
        store.readBundle(stage)
        assertEquals(listOf("manifest.json", "manifest.json.sig", "dns-categories.fhds"), io.reads)
        lease.release()
    }

    @Test
    fun postAtomicStageFailureAndRollbackFailureRetainPrimaryAndRecover() = runBlocking {
        val root = temporary.newFolder()
        val io = CountIo()
        val store = CategorizedDnsStagedStore(root, io)
        val old = store.stage(bundle())
        store.promote(old) { true }
        val pending = store.stage(bundle(2uL))
        io.postFault = OwnedDnsPostReplaceFailure()
        io.rollbackFault = OwnedDnsRollbackFailure()
        val actual = assertThrows(IOException::class.java) { runBlocking { store.stage(bundle(3uL)) } }
        assertSame(io.postFault, actual)
        assertTrue(actual.suppressed.single() === io.rollbackFault)
        assertTrue(File(pending.directory.parentFile, "staging").isFile)
        val reopened = CategorizedDnsStagedStore(root)
        assertEquals(old, reopened.activeOrNull("normal-medium"))
        assertEquals(pending, reopened.pendingOrNull("normal-medium"))
    }

    private class CountIo : CategorizedDnsStoreIo {
        val reads = mutableListOf<String>()
        var postFault: IOException? = null
        var rollbackFault: IOException? = null
        var replacementCount = 0

        override fun replace(parent: File, name: String, bytes: ByteArray) {
            if (name == "pending" && postFault != null) {
                replacementCount++
                if (replacementCount > 1) throw requireNotNull(rollbackFault)
                FileCategorizedDnsStoreIo.replace(parent, name, bytes)
                throw requireNotNull(postFault)
            }
            FileCategorizedDnsStoreIo.replace(parent, name, bytes)
        }

        override fun read(file: File): ByteArray {
            reads += file.name
            return file.readBytes()
        }

        override fun remove(file: File): Boolean = FileCategorizedDnsStoreIo.remove(file)
    }
}

private class OwnedDnsPostReplaceFailure(val marker: Any = Any()) : IOException("owned post replace primary")
private class OwnedDnsRollbackFailure(val marker: Any = Any()) : IOException("owned rollback secondary")
