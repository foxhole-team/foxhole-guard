package com.foxhole.guard.core.sentinel.scanner

import com.foxhole.guard.core.sentinel.FileCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScannerBaselineCursorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cipher = object : FileCipher {
        override fun readBytes(file: File): ByteArray = file.readBytes()
        override fun writeBytesAtomic(file: File, plaintext: ByteArray) { file.writeBytes(plaintext) }
    }

    @Test fun completedChunksResumeAfterReopenAndNeverSkipUnfinishedChunk() {
        val file = File(temporary.newFolder(), "cursor")
        val store = ScannerBaselineCursorStore(file, cipher)
        val names = (1..20).map { "org.owned.app$it" }
        assertEquals(names.take(8), store.next("epoch", names))
        assertEquals(names.take(8), ScannerBaselineCursorStore(file, cipher).next("epoch", names))
        store.advance("epoch", 8)
        assertEquals(names.drop(8).take(8), ScannerBaselineCursorStore(file, cipher).next("epoch", names))
        store.advance("epoch", 8)
        assertEquals(names.drop(16), store.next("epoch", names))
        store.advance("epoch", 4)
        assertEquals(emptyList<String>(), store.next("epoch", names))
    }

    @Test fun resetEpochCannotReuseOldProgressAndExplicitClearDeletesOwnedCursor() {
        val file = File(temporary.newFolder(), "cursor")
        val store = ScannerBaselineCursorStore(file, cipher)
        val names = (1..10).map { "org.owned.app$it" }
        store.next("old", names)
        store.advance("old", 8)
        assertEquals(names.take(8), store.next("new", names))
        store.clear()
        assertFalse(file.exists())
    }

    @Test fun malformedOrOversizedCursorCannotSilentlyBecomeComplete() {
        val file = File(temporary.newFolder(), "cursor")
        file.writeText("{\"epoch\":\"e\",\"packages\":[],\"next\":-1}")
        assertThrows(
            IllegalArgumentException::class.java
        ) { ScannerBaselineCursorStore(file, cipher).next("e", emptyList()) }
    }

    @Test fun oversizedAdvanceCannotSkipAChunkOrCorruptSavedProgress() {
        val file = File(temporary.newFolder(), "cursor")
        val store = ScannerBaselineCursorStore(file, cipher)
        val names = (1..20).map { "org.owned.app$it" }
        store.next("epoch", names)
        assertThrows(IllegalArgumentException::class.java) { store.advance("epoch", 9) }
        assertEquals(names.take(8), store.next("epoch", names))
    }

    @Test fun changingVisibleInputDuringResumeDoesNotRewriteTheCapturedBaseline() {
        val file = File(temporary.newFolder(), "cursor")
        val store = ScannerBaselineCursorStore(file, cipher)
        val names = (1..10).map { "org.owned.app$it" }
        store.next("epoch", names)
        store.advance("epoch", 8)
        assertEquals(names.drop(8), store.next("epoch", listOf("org.owned.changed")))
    }

    @Test fun duplicateAcknowledgementCannotSkipTheNextChunk() {
        val file = File(temporary.newFolder(), "cursor")
        val store = ScannerBaselineCursorStore(file, cipher)
        val names = (1..20).map { "org.owned.app$it" }
        val lease = store.claim("epoch", names)
        assertEquals(names.take(8), lease.packages)
        assertEquals(true, store.acknowledge(lease))
        assertEquals(false, store.acknowledge(lease))
        assertEquals(names.drop(8).take(8), store.claim("epoch", names).packages)
    }

    @Test fun oldLeaseAfterResetOrDifferentChunkCannotAdvanceCurrentCursor() {
        val file = File(temporary.newFolder(), "cursor")
        val store = ScannerBaselineCursorStore(file, cipher)
        val names = (1..20).map { "org.owned.app$it" }
        val old = store.claim("old", names)
        val current = store.claim("new", names)
        assertEquals(false, store.acknowledge(old))
        assertEquals(false, store.acknowledge(current.copy(packages = listOf("org.owned.other"))))
        assertEquals(names.take(8), store.claim("new", names).packages)
        store.clear()
        assertEquals(false, store.acknowledge(current))
    }

    @Test fun failedAtomicWriteRetainsUnacknowledgedChunk() {
        val file = File(temporary.newFolder(), "cursor")
        var failWrite = false
        val failing = object : FileCipher {
            override fun readBytes(file: File): ByteArray = file.readBytes()
            override fun writeBytesAtomic(file: File, plaintext: ByteArray) {
                if (failWrite) throw java.io.IOException("Owned write failure")
                file.writeBytes(plaintext)
            }
        }
        val store = ScannerBaselineCursorStore(file, failing)
        val names = (1..20).map { "org.owned.app$it" }
        val lease = store.claim("epoch", names)
        failWrite = true
        assertThrows(java.io.IOException::class.java) { store.acknowledge(lease) }
        assertEquals(names.take(8), store.claim("epoch", names).packages)
    }
}
