package com.foxhole.guard.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

class PackagedCategorizedDnsSeedStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun artifact(): ByteArray = ByteArray(100).also { bytes ->
        "FHDNS1\u0000\u0000".toByteArray().copyInto(bytes)
        ByteBuffer.wrap(bytes).putLong(8, 1).putLong(16, 0).putLong(24, 20).putLong(32, 0)
    }

    private fun entry(bytes: ByteArray) = CategorizedDnsApkEntry(
        "foxhole-dns-categories-normal-medium",
        "dns-categories/normal-medium/dns-categories.fhds",
        CategorizedDnsContract.sha256(bytes),
        bytes.size.toULong(),
        1uL,
        CategorizedDnsCounts(1uL, 0uL, 0uL, 0uL),
        "dns-categories/normal-medium/source-info.json",
    )

    @Test fun repeatedPreparationDoesNotRereadOrRewritePackagedBytes() = runBlocking {
        val store = PackagedCategorizedDnsSeedStore(temp.newFolder())
        val bytes = artifact()
        var reads = 0
        val first = store.prepare(entry(bytes)) {
            reads++
            bytes
        }
        val stamp = first.lastModified()
        val second = store.prepare(entry(bytes)) {
            reads++
            bytes
        }
        assertEquals(first, second)
        assertEquals(1, reads)
        assertEquals(stamp, second.lastModified())
    }

    @Test fun corruptCachedBytesAreRepairedOnlyFromVerifiedPackage() = runBlocking {
        val store = PackagedCategorizedDnsSeedStore(temp.newFolder())
        val bytes = artifact()
        val file = store.prepare(entry(bytes)) { bytes }
        file.writeBytes(ByteArray(100))
        assertThrows(
            IllegalArgumentException::class.java
        ) { runBlocking { store.prepare(entry(bytes)) { ByteArray(100) } } }
        store.prepare(entry(bytes)) { bytes }
        assertEquals(entry(bytes).sha256, CategorizedDnsContract.sha256(file.readBytes()))
    }

    @Test fun cancellationRetainsPreviouslyVerifiedSeed() = runBlocking {
        val store = PackagedCategorizedDnsSeedStore(temp.newFolder())
        val bytes = artifact()
        val file = store.prepare(entry(bytes)) { bytes }
        val newBytes = bytes.copyOf().also { it[90] = 1 }
        val failure = CancellationException("owned cancelled asset read")
        assertSame(failure, runCatching { store.prepare(entry(newBytes)) { throw failure } }.exceptionOrNull())
        assertEquals(entry(bytes).sha256, CategorizedDnsContract.sha256(file.readBytes()))
    }

    @Test fun symlinkSeedCannotBecomePackageOwnedData() = runBlocking {
        val root = temp.newFolder()
        val parent = File(root, "normal-medium").apply { mkdirs() }
        val outside = temp.newFile().apply { writeBytes(artifact()) }
        Files.createSymbolicLink(File(parent, "dns-categories.fhds").toPath(), outside.toPath())
        var read = false
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            runBlocking {
                PackagedCategorizedDnsSeedStore(
                    root
                ).prepare(entry(artifact())) {
                    read = true
                    artifact()
                }
            }
        }
        assertFalse(read)
    }
}
