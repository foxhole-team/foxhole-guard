package com.foxhole.guard.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShareVaultKeySourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `creates and reuses one 32-byte key`() {
        val keyFile = File(temporaryFolder.newFolder("keys"), "share.key")
        val cipher = FakeFileCipher()
        val generated = ByteArray(32) { index -> (index + 1).toByte() }
        var generations = 0
        val source =
            ShareVaultKeySource(
                keyFile = keyFile,
                cipher = cipher,
                readMigrating = cipher::readBytes,
                randomKey = {
                    generations += 1
                    generated.copyOf()
                },
            )

        assertArrayEquals(generated, source.acquireKey())
        assertArrayEquals(generated, source.acquireKey())
        assertEquals(1, generations)
    }

    @Test
    fun `corrupt existing key fails closed without rekeying`() {
        val keyFile = File(temporaryFolder.newFolder("keys"), "share.key").apply {
            writeBytes(ByteArray(7) { 4 })
        }
        var generated = false
        val cipher = FakeFileCipher()
        val source =
            ShareVaultKeySource(
                keyFile = keyFile,
                cipher = cipher,
                readMigrating = cipher::readBytes,
                randomKey = {
                    generated = true
                    ByteArray(32)
                },
            )

        assertThrows(ShareVaultKeyUnavailableException::class.java) { source.acquireKey() }
        assertEquals(false, generated)
        assertEquals(7, keyFile.length())
    }

    @Test
    fun `unreadable existing key fails closed without rekeying`() {
        val keyFile = File(temporaryFolder.newFolder("keys"), "share.key").apply { writeText("sealed") }
        var generated = false
        val source =
            ShareVaultKeySource(
                keyFile = keyFile,
                cipher = FakeFileCipher(),
                readMigrating = { throw IllegalStateException("keystore unavailable") },
                randomKey = {
                    generated = true
                    ByteArray(32)
                },
            )

        assertThrows(ShareVaultKeyUnavailableException::class.java) { source.acquireKey() }
        assertEquals(false, generated)
    }
}
