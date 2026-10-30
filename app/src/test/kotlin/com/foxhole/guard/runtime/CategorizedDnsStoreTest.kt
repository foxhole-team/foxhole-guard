package com.foxhole.guard.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

class CategorizedDnsStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun stagingNeverChangesLastGoodUntilExactOwnerPromotion() = runBlocking {
        val store = store()
        val first = store.stage(bundle())
        assertNull(store.activeOrNull("normal-medium"))
        assertEquals(first, store.pendingOrNull("normal-medium"))
        assertEquals(CategorizedDnsPromotion.PROMOTED, store.promote(first) { true })
        val next = store.stage(bundle(2uL))
        assertEquals(first, store.activeOrNull("normal-medium"))
        assertEquals(next, store.pendingOrNull("normal-medium"))
    }

    @Test
    fun supersededTokenAndLostOwnerCannotPromote() = runBlocking {
        val store = store()
        val first = store.stage(bundle())
        val next = store.stage(bundle(2uL))
        assertEquals(CategorizedDnsPromotion.SUPERSEDED, store.promote(first) { true })
        assertEquals(CategorizedDnsPromotion.SUPERSEDED, store.promote(next) { false })
        assertNull(store.activeOrNull("normal-medium"))
    }

    @Test
    fun rollbackAndSameSequenceChangedBytesAreRejected() = runBlocking {
        val store = store()
        val first = store.stage(bundle(2uL))
        store.promote(first) { true }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.stage(bundle(1uL)) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.stage(bundle(2uL, changed = true)) }
        }
        assertEquals(first, store.activeOrNull("normal-medium"))
    }

    @Test
    fun separateVariantHasIndependentFloorAndIdentity() = runBlocking {
        val store = store()
        val normal = store.stage(bundle(9uL))
        store.promote(normal) { true }
        val pro = store.stage(bundle(1uL, variant = "pro-medium"))
        assertEquals(1uL, pro.sequence)
        assertEquals(normal, store.activeOrNull("normal-medium"))
        assertNull(store.activeOrNull("pro-medium"))
    }

    @Test
    fun fullUnsignedFloorSurvivesDiskRoundtrip() = runBlocking {
        val store = store()
        val maximum = store.stage(bundle(ULong.MAX_VALUE))
        store.promote(maximum) { true }
        assertEquals(ULong.MAX_VALUE, store.activeOrNull("normal-medium")?.sequence)
    }

    @Test
    fun mutableInputArraysCannotAlterPersistedCapture() = runBlocking {
        val store = store()
        val input = bundle()
        val stage = store.stage(input)
        input.artifactBytes[79] = 1
        input.manifestBytes[0] = 0
        assertEquals(stage, store.pendingOrNull("normal-medium"))
        assertNotEquals(input.artifactBytes[79], File(stage.directory, "dns-categories.fhds").readBytes()[79])
    }

    @Test
    fun symbolicPointerCannotReadOutsideOwnedStore() = runBlocking {
        val root = temporary.newFolder("store")
        val store = CategorizedDnsStagedStore(root)
        val stage = store.stage(bundle())
        val pointer = File(root, "normal-medium/pending")
        assertTrue(pointer.delete())
        Files.createSymbolicLink(pointer.toPath(), temporary.newFile("outside").toPath())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.pendingOrNull("normal-medium") }
        }
        assertTrue(stage.directory.isDirectory)
    }

    @Test
    fun ownerRevokedDuringAtomicPromotionRestoresPreviousActive() = runBlocking {
        val store = store()
        val first = store.stage(bundle())
        store.promote(first) { true }
        val next = store.stage(bundle(2uL))
        var checks = 0
        assertEquals(CategorizedDnsPromotion.SUPERSEDED, store.promote(next) { ++checks < 3 })
        assertEquals(first, store.activeOrNull("normal-medium"))
        assertEquals(next, store.pendingOrNull("normal-medium"))
    }

    @Test
    fun cancellationAfterAtomicSwitchRestoresPreviousActiveAndPropagates() = runBlocking {
        val store = store()
        val first = store.stage(bundle())
        store.promote(first) { true }
        val next = store.stage(bundle(2uL))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                store.promote(next) { if (++checks == 3) throw CancellationException("owned") else true }
            }
        }
        assertEquals(first, store.activeOrNull("normal-medium"))
        assertFalse(File(next.directory.parentFile, "active").readText().startsWith(next.token))
    }

    private fun store() = CategorizedDnsStagedStore(temporary.newFolder("store"))
}

internal fun bundle(sequence: ULong = 1uL, variant: String = "normal-medium", changed: Boolean = false): VerifiedCategorizedDnsRuleSet {
    val sourceHash = "b".repeat(64)
    val artifact = ByteArray(100)
    "FHDNS1".encodeToByteArray().copyInto(artifact)
    val buffer = ByteBuffer.wrap(artifact)
    buffer.putLong(8, 1)
    buffer.putLong(16, 0)
    buffer.putLong(24, 20)
    buffer.putLong(32, 0)
    ByteArray(32) { 0xbb.toByte() }.copyInto(artifact, 40)
    if (changed) artifact[99] = 1
    val tiers = variant.split('-')
    val manifest = CategorizedDnsManifest(
        3, CategorizedDnsContract.NAME_PREFIX + variant, "foxhole-dns-fst-v1", sequence, 1000uL, 2000uL,
        "a".repeat(64), listOf("ads"), CategorizedDnsTiers(tiers[0], tiers[1]), sourceHash,
        listOf(
            CategorizedDnsSource(
                "owned",
                "https://example.invalid/owned",
                "c".repeat(40),
                "CC0-1.0",
                listOf(CategorizedDnsInput("owned.txt", "d".repeat(64))),
                listOf("ads")
            )
        ),
        CategorizedDnsArtifact(
            "dns-categories.fhds",
            100uL,
            CategorizedDnsContract.sha256(artifact),
            1uL,
            0uL,
            CategorizedDnsCounts(0uL, 0uL, 0uL, 1uL)
        ),
        CategorizedDnsCompatibility(1, 1),
    )
    val bytes = (canonicalAsciiJson(Json.parseToJsonElement(Json.encodeToString(manifest))) + "\n").encodeToByteArray()
    return VerifiedCategorizedDnsRuleSet(manifest, bytes, byteArrayOf(1), artifact)
}
