package com.foxhole.beta.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class AesGcmFileEnvelopeTest {
    @Test
    fun `round trips AES GCM payload envelope`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val plaintext = ByteArray(64).also(SecureRandom()::nextBytes)

        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, key)
        val encoded =
            AesGcmFileEnvelope.encode(
                iv = encryptCipher.iv,
                ciphertext = encryptCipher.doFinal(plaintext),
            )

        val decoded = AesGcmFileEnvelope.decode(encoded)
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decoded.iv))

        assertArrayEquals(plaintext, decryptCipher.doFinal(decoded.ciphertext))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsupported envelope version`() {
        AesGcmFileEnvelope.decode(byteArrayOf(9, 0, 0, 0, 1, 1, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects encoding AES GCM envelope with short iv`() {
        AesGcmFileEnvelope.encode(
            iv = ByteArray(11),
            ciphertext = byteArrayOf(1),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects decoding AES GCM envelope with long iv`() {
        AesGcmFileEnvelope.decode(
            byteArrayOf(1, 0, 0, 0, 13) + ByteArray(13) + byteArrayOf(1),
        )
    }
}
