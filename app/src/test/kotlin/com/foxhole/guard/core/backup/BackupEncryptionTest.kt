package com.foxhole.guard.core.backup

import com.foxhole.core.model.ProfileSourceType
import com.foxhole.guard.core.security.GuardCrypto
import com.foxhole.guard.core.security.GuardKdfParams
import com.foxhole.guard.core.security.GuardKeypair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

class BackupEncryptionTest {
    private val crypto = DeterministicTestKdf()
    private val document =
        BackupDocument(
            settingsSchemaVersion = com.foxhole.core.model.Settings().schemaVersion,
            settings = com.foxhole.core.model.Settings(),
            profiles =
            listOf(
                BackupProfilePayload(
                    backupId = 9,
                    name = "private profile",
                    sourceType = ProfileSourceType.RAW_CONFIG_JSON.name,
                    rawInput = "vless://profile-secret@example.test",
                ),
            ),
        )

    @Test
    fun `full backup round trips without exposing profile credentials`() {
        val password = "correct horse battery staple".toCharArray()
        val envelope = encryptBackupDocument(document, password, crypto, deterministicRandom())

        assertTrue(envelope.contains(ENCRYPTED_BACKUP_FORMAT))
        assertFalse(envelope.contains("profile-secret"))
        val result = decryptBackupDocument(envelope, "correct horse battery staple".toCharArray(), crypto)
        assertTrue(result is BackupParseResult.Success)
        assertEquals(
            document.profiles,
            (result as BackupParseResult.Success).document.profiles,
        )
    }

    @Test
    fun `wrong password and tampering fail closed with one indistinguishable result`() {
        val envelope =
            encryptBackupDocument(
                document,
                "correct horse battery staple".toCharArray(),
                crypto,
                deterministicRandom(),
            )
        assertEquals(
            BackupParseResult.Failure.WRONG_PASSWORD_OR_CORRUPT,
            decryptBackupDocument(envelope, "incorrect password".toCharArray(), crypto),
        )
        val marker = "\"ciphertext\": \""
        val valueStart = envelope.indexOf(marker) + marker.length
        val replacement = if (envelope[valueStart] == 'A') 'B' else 'A'
        val tampered = envelope.replaceRange(valueStart, valueStart + 1, replacement.toString())
        assertEquals(
            BackupParseResult.Failure.WRONG_PASSWORD_OR_CORRUPT,
            decryptBackupDocument(tampered, "correct horse battery staple".toCharArray(), crypto),
        )
    }

    @Test
    fun `portable file cap includes base64 expansion of the largest accepted ciphertext`() {
        val encodedCiphertextBytes = ((MAX_BACKUP_CIPHERTEXT_BYTES + 2L) / 3L) * 4L

        assertTrue(encodedCiphertextBytes < MAX_ENCRYPTED_BACKUP_FILE_BYTES)
        assertEquals(16, MAX_BACKUP_CIPHERTEXT_BYTES - MAX_BACKUP_PLAINTEXT_BYTES)
    }

    @Test
    fun `portable plaintext bound accepts the edge and rejects one byte more`() {
        requirePortableBackupPlaintextSize(MAX_BACKUP_PLAINTEXT_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            requirePortableBackupPlaintextSize(MAX_BACKUP_PLAINTEXT_BYTES + 1)
        }
    }

    private fun deterministicRandom(): SecureRandom =
        object : SecureRandom() {
            private var next = 1

            override fun nextBytes(bytes: ByteArray) {
                bytes.indices.forEach { index -> bytes[index] = (next++ and 0xff).toByte() }
            }
        }
}

private class DeterministicTestKdf : GuardCrypto {
    override fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        params: GuardKdfParams,
        outputLength: Int,
    ): ByteArray {
        val seed =
            password + salt +
                byteArrayOf(params.ops.toByte(), params.parallelism.toByte()) +
                params.memKib.toString().encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(seed)
        return ByteArray(outputLength) { index -> digest[index % digest.size] }
    }

    override fun sealedBoxKeypair(): GuardKeypair = error("not used")

    override fun seal(payload: ByteArray, publicKey: ByteArray): ByteArray = error("not used")

    override fun openSealed(
        sealed: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray = error("not used")
}
