package com.foxhole.guard.core.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecureKeyboxTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var keyboxFile: File
    private lateinit var attemptsFile: File
    private lateinit var crypto: FakeGuardCrypto
    private lateinit var keybox: SecureKeybox

    private val password = "correct horse battery staple".encodeToByteArray()
    private val dataKey = ByteArray(SecureKeybox.DATA_KEY_BYTES) { (it + 1).toByte() }
    private val kdf = GuardKdfParams(memKib = 1024, ops = 1)

    @Before
    fun setUp() {
        val root = temporaryFolder.newFolder("secure")
        keyboxFile = File(root, "keybox.bin")
        attemptsFile = File(root, "attempts.bin")
        crypto = FakeGuardCrypto()
        keybox = SecureKeybox(keyboxFile, attemptsFile, FakeFileCipher(), FakeFileCipher(), crypto)
    }

    @Test
    fun `create then unlock round-trips dataKey and guard keypair`() {
        val created = keybox.create(password, dataKey, kdf)
        val outcome = keybox.unlock(password)

        assertTrue(outcome is KeyboxUnlockOutcome.Success)
        val session = (outcome as KeyboxUnlockOutcome.Success).session
        assertArrayEquals(dataKey, session.dataKey)
        assertArrayEquals(created.guardPrivateKey, session.guardPrivateKey)
        assertArrayEquals(created.guardPublicKey, session.guardPublicKey)
        assertArrayEquals(created.guardPublicKey, keybox.guardPublicKeyOrNull())
    }

    @Test
    fun `wrong password is reported without corruption and counts attempts`() {
        keybox.create(password, dataKey, kdf)

        val first = keybox.unlock("wrong".encodeToByteArray())
        val second = keybox.unlock("wrong again".encodeToByteArray())

        assertTrue(first is KeyboxUnlockOutcome.WrongPassword)
        assertTrue(second is KeyboxUnlockOutcome.WrongPassword)
        assertEquals(1, (first as KeyboxUnlockOutcome.WrongPassword).failedAttempts)
        assertEquals(2, (second as KeyboxUnlockOutcome.WrongPassword).failedAttempts)
        assertEquals(SecureKeybox.FIRST_BACKOFF_MS, first.nextDelayMs)
        assertEquals(SecureKeybox.FIRST_BACKOFF_MS * 2, second.nextDelayMs)
    }

    @Test
    fun `verifyPassword confirms the credential without arming the unlock backoff`() {
        keybox.create(password, dataKey, kdf)
        keybox.unlock("wrong".encodeToByteArray())
        val attemptsBefore = keybox.readAttempts().failedAttempts
        assertEquals(1, attemptsBefore)

        assertTrue(keybox.verifyPassword(password))
        assertFalse(keybox.verifyPassword("nope".encodeToByteArray()))

        assertEquals(attemptsBefore, keybox.readAttempts().failedAttempts)
    }

    @Test
    fun `verifyPassword returns false when no keybox exists`() {
        assertFalse(keybox.verifyPassword(password))
    }

    @Test
    fun `remaining backoff is persisted and survives a fresh keybox instance`() {
        var now = 100_000L
        val clocked =
            SecureKeybox(
                keyboxFile,
                attemptsFile,
                FakeFileCipher(),
                FakeFileCipher(),
                crypto,
                wallClock = { now },
            )
        clocked.create(password, dataKey, kdf)

        clocked.unlock("wrong".encodeToByteArray())
        assertEquals(SecureKeybox.FIRST_BACKOFF_MS, clocked.remainingBackoffMs())

        val restarted =
            SecureKeybox(
                keyboxFile,
                attemptsFile,
                FakeFileCipher(),
                FakeFileCipher(),
                crypto,
                wallClock = { now },
            )
        now += 400
        assertEquals(SecureKeybox.FIRST_BACKOFF_MS - 400, restarted.remainingBackoffMs())

        now += SecureKeybox.FIRST_BACKOFF_MS
        assertEquals(0L, restarted.remainingBackoffMs())

        now = 0L
        assertEquals(SecureKeybox.FIRST_BACKOFF_MS, restarted.remainingBackoffMs())
    }

    @Test
    fun `successful unlock resets the failed-attempt counter`() {
        keybox.create(password, dataKey, kdf)
        keybox.unlock("wrong".encodeToByteArray())
        keybox.unlock(password)

        assertEquals(0, keybox.readAttempts().failedAttempts)
    }

    @Test
    fun `change password keeps dataKey and accepts only the new password`() {
        val session = keybox.create(password, dataKey, kdf)
        val newPassword = "new password".encodeToByteArray()
        session.changePassword(newPassword)

        val oldOutcome = keybox.unlock(password)
        val newOutcome = keybox.unlock(newPassword)

        assertTrue(oldOutcome is KeyboxUnlockOutcome.WrongPassword)
        assertTrue(newOutcome is KeyboxUnlockOutcome.Success)
        assertArrayEquals(dataKey, (newOutcome as KeyboxUnlockOutcome.Success).session.dataKey)
    }

    @Test
    fun `journal checkpoint survives reopen and rides the password domain`() {
        val session = keybox.create(password, dataKey, kdf)
        session.updateJournalCheckpoint(seq = 41, headHash = "abc123")

        val reopened = keybox.unlock(password)

        assertTrue(reopened is KeyboxUnlockOutcome.Success)
        assertEquals(41L, (reopened as KeyboxUnlockOutcome.Success).session.checkpoint.seq)
        assertEquals("abc123", reopened.session.checkpoint.headHash)
        assertEquals(41L, keybox.checkpointOrNull()?.seq)
    }

    @Test
    fun `tampering AAD-bound metadata breaks unlock with a corruption verdict`() {
        keybox.create(password, dataKey, kdf)
        val json = Json { ignoreUnknownKeys = true }
        val document = json.decodeFromString<KeyboxDocument>(keyboxFile.readText())
        val tampered = document.copy(checkpoint = KeyboxCheckpoint(seq = 999, headHash = "forged"))
        keyboxFile.writeText(json.encodeToString(KeyboxDocument.serializer(), tampered))

        val outcome = keybox.unlock(password)

        assertTrue(outcome is KeyboxUnlockOutcome.Corrupted)
    }

    @Test
    fun `tampering the guard public key breaks unlock`() {
        keybox.create(password, dataKey, kdf)
        val json = Json { ignoreUnknownKeys = true }
        val document = json.decodeFromString<KeyboxDocument>(keyboxFile.readText())
        val foreignKey = java.util.Base64.getEncoder().encodeToString(ByteArray(GuardCrypto.X25519_KEY_BYTES) { 7 })
        keyboxFile.writeText(
            json.encodeToString(KeyboxDocument.serializer(), document.copy(guardPublicKey = foreignKey))
        )

        val outcome = keybox.unlock(password)

        assertTrue(outcome is KeyboxUnlockOutcome.Corrupted)
    }

    @Test
    fun `garbage file is corrupted, missing file is missing`() {
        assertTrue(keybox.unlock(password) is KeyboxUnlockOutcome.Missing)

        keyboxFile.parentFile?.mkdirs()
        keyboxFile.writeText("not a keybox at all")
        assertTrue(keybox.unlock(password) is KeyboxUnlockOutcome.Corrupted)
    }

    @Test
    fun `backoff grows exponentially and caps at one minute`() {
        assertEquals(0L, SecureKeybox.backoffDelayMs(0))
        assertEquals(1_000L, SecureKeybox.backoffDelayMs(1))
        assertEquals(2_000L, SecureKeybox.backoffDelayMs(2))
        assertEquals(4_000L, SecureKeybox.backoffDelayMs(3))
        assertEquals(32_000L, SecureKeybox.backoffDelayMs(6))
        assertEquals(60_000L, SecureKeybox.backoffDelayMs(7))
        assertEquals(60_000L, SecureKeybox.backoffDelayMs(50))
    }

    @Test
    fun `destroy zeroizes every held key`() {
        val session = keybox.create(password, dataKey, kdf)
        val heldDataKey = session.dataKey
        val heldPrivateKey = session.guardPrivateKey

        session.destroy()

        assertArrayEquals(ByteArray(heldDataKey.size), heldDataKey)
        assertArrayEquals(ByteArray(heldPrivateKey.size), heldPrivateKey)
        assertFalse(dataKey.all { it == 0.toByte() })
    }

    @Test
    fun `sealed payloads round-trip and refuse a wrong private key`() {
        val keypair = crypto.sealedBoxKeypair()
        val other = crypto.sealedBoxKeypair()
        val payload = "guard journal entry".encodeToByteArray()

        val sealed = crypto.seal(payload, keypair.publicKey)

        assertArrayEquals(payload, crypto.openSealed(sealed, keypair.publicKey, keypair.privateKey))
        val wrongKey = runCatching { crypto.openSealed(sealed, other.publicKey, other.privateKey) }
        assertTrue(wrongKey.isFailure)
    }

    @Test
    fun `keybox document on disk never contains raw secrets`() {
        keybox.create(password, dataKey, kdf)
        val raw = Json.parseToJsonElement(keyboxFile.readText()).jsonObject
        val dataKey64 = java.util.Base64.getEncoder().encodeToString(dataKey)

        assertEquals(SecureKeybox.KEYBOX_VERSION, raw.getValue("version").jsonPrimitive.content.toInt())
        assertFalse(keyboxFile.readText().contains(dataKey64))
    }

    @Test
    fun `legacy plain-json keybox unlocks and migrates to the wrapped format`() {
        keybox.create(password, dataKey, kdf, credential = KeyboxDocument.CREDENTIAL_PASSWORD).destroy()
        assertEquals('{'.code.toByte(), keyboxFile.readBytes()[0])

        val wrapped = SecureKeybox(keyboxFile, attemptsFile, FakeFileCipher(), XorFileCipher(), crypto)
        val outcome = wrapped.unlock(password)

        assertTrue(outcome is KeyboxUnlockOutcome.Success)
        (outcome as KeyboxUnlockOutcome.Success).session.destroy()
        assertFalse(keyboxFile.readBytes()[0] == '{'.code.toByte())
        assertEquals(KeyboxDocument.CREDENTIAL_PASSWORD, wrapped.credentialKindOrNull())
        val again = wrapped.unlock(password)
        assertTrue(again is KeyboxUnlockOutcome.Success)
        (again as KeyboxUnlockOutcome.Success).session.destroy()
    }

    @Test
    fun `unlock with master key opens the box without argon2 and rejects a stale key`() {
        val created = keybox.create(password, dataKey, kdf)
        val masterKey = created.copyMasterKey()
        created.destroy()

        val argonRunsBefore = crypto.argon2Invocations
        val outcome = keybox.unlockWithMasterKey(masterKey)

        assertTrue(outcome is KeyboxUnlockOutcome.Success)
        assertArrayEquals(dataKey, (outcome as KeyboxUnlockOutcome.Success).session.dataKey)
        assertEquals(argonRunsBefore, crypto.argon2Invocations)
        outcome.session.destroy()

        val stale = keybox.unlockWithMasterKey(ByteArray(masterKey.size) { 7 })
        assertTrue(stale is KeyboxUnlockOutcome.Corrupted)
    }
}
