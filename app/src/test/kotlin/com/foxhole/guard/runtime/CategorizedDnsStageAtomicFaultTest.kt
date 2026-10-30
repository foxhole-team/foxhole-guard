package com.foxhole.guard.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CategorizedDnsStageAtomicFaultTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun postAtomicPendingWriteFailureRestoresOldUsablePointers() = runBlocking {
        val io = PostWriteFault()
        val store = CategorizedDnsStagedStore(temporary.newFolder(), io)
        val active = store.stage(bundle())
        store.promote(active) { true }
        val pending = store.stage(bundle(2uL))
        io.armed = true
        assertThrows(IOException::class.java) { runBlocking { store.stage(bundle(3uL)) } }
        assertEquals(active, store.activeOrNull("normal-medium"))
        assertEquals(pending, store.pendingOrNull("normal-medium"))
    }

    private class PostWriteFault : CategorizedDnsStoreIo {
        var armed = false
        override fun replace(parent: File, name: String, bytes: ByteArray) {
            FileCategorizedDnsStoreIo.replace(parent, name, bytes)
            if (name == "pending" && armed) {
                armed = false
                throw IOException("owned post-atomic pending fault")
            }
        }
        override fun remove(file: File): Boolean = FileCategorizedDnsStoreIo.remove(file)
    }
}
