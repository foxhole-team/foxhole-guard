package com.foxhole.guard.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabaseKeySourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var passphraseFile: File
    private var keyboxPresent = false
    private var keystoreAliasDeleted = false

    @Before
    fun setUp() {
        passphraseFile = File(temporaryFolder.newFolder("keys"), "profile-db.passphrase")
        keyboxPresent = false
        keystoreAliasDeleted = false
    }

    private fun source(): KeystoreDatabaseKeySource {
        val cipher = FakeFileCipher()
        return KeystoreDatabaseKeySource(
            passphraseFile = passphraseFile,
            cipher = cipher,
            readMigrating = { file -> cipher.readBytes(file) },
            keyboxExists = { keyboxPresent },
            deleteKeystoreAlias = { keystoreAliasDeleted = true },
        )
    }

    @Test
    fun `creates a stable 32-byte passphrase on first use`() {
        val first = source().acquirePassphrase()
        val second = source().acquirePassphrase()

        assertArrayEquals(first, second)
        assertTrue(first.size == 32)
    }

    @Test
    fun `refuses to mint a fresh passphrase while a keybox exists`() {
        keyboxPresent = true

        assertThrows(DatabaseKeyUnavailableException::class.java) {
            source().acquirePassphrase()
        }
        assertFalse(passphraseFile.exists())
    }

    @Test
    fun `still serves an existing passphrase even when a keybox exists`() {
        val original = source().acquirePassphrase()
        keyboxPresent = true

        assertArrayEquals(original, source().acquirePassphrase())
    }

    @Test
    fun `migration round-trips the same data key keystore to keybox to keystore`() {
        val keystore = source()
        val dataKey = keystore.acquirePassphrase()
        val crypto = FakeGuardCrypto()
        val keybox =
            SecureKeybox(
                keyboxFile = File(temporaryFolder.newFolder("secure1"), "keybox.bin"),
                attemptsFile = File(temporaryFolder.root, "attempts.bin"),
                attemptsCipher = FakeFileCipher(),
                keyboxCipher = FakeFileCipher(),
                crypto = crypto,
            )

        val session = keybox.create("pw".encodeToByteArray(), dataKey, GuardKdfParams(memKib = 1024, ops = 1))
        keyboxPresent = true
        keystore.delete()
        assertTrue(keystoreAliasDeleted)
        assertFalse(keystore.exists())

        val reopened = keybox.unlock("pw".encodeToByteArray())
        assertTrue(reopened is KeyboxUnlockOutcome.Success)
        keystore.writeBack((reopened as KeyboxUnlockOutcome.Success).session.dataKey)
        keyboxPresent = false

        assertArrayEquals(session.dataKey, keystore.acquirePassphrase())
    }

    @Test
    fun `keybox source fails closed without a session and serves it after unlock`() {
        val session = SecureSessionHolder()
        val keyboxSource = KeyboxDatabaseKeySource(session)

        assertThrows(DatabaseKeyUnavailableException::class.java) { keyboxSource.acquirePassphrase() }

        val dataKey = ByteArray(32) { (it + 5).toByte() }
        session.install(dataKey, ByteArray(32), ByteArray(32))
        assertArrayEquals(dataKey, keyboxSource.acquirePassphrase())
    }
}
