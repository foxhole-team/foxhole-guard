package com.foxhole.guard.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CategorizedDnsPendingFloorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun pendingHigherSequenceCannotBeReplacedByOlderIncoming() = runBlocking {
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val active = store.stage(bundle(8uL))
        store.promote(active) { true }
        val pending = store.stage(bundle(10uL))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.stage(bundle(9uL)) } }
        assertEquals(pending, store.pendingOrNull("normal-medium"))
    }

    @Test
    fun samePendingSequenceCannotBindDifferentBytes() = runBlocking {
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val pending = store.stage(bundle(10uL))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.stage(bundle(10uL, changed = true)) }
        }
        assertEquals(pending, store.pendingOrNull("normal-medium"))
    }

    @Test
    fun repeatedSameVerifiedPendingIdentityIsIdempotent() = runBlocking {
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val input = bundle(10uL)
        val pending = store.stage(input)
        assertEquals(pending, store.stage(input))
    }

    @Test
    fun completedGenerationsHaveBoundedOwnedDirectoryRetention() = runBlocking {
        val root = temporary.newFolder()
        val store = CategorizedDnsStagedStore(root)
        for (sequence in 1uL..8uL) {
            val stage = store.stage(bundle(sequence))
            store.promote(stage) { true }
        }
        val owned = File(root, "normal-medium").listFiles().orEmpty().count { it.isDirectory }
        assertTrue("retained=$owned", owned <= 3)
    }
}
