package com.foxhole.guard.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class BiometricKeyboxGate(
    private val bioFile: File,
    private val recordDiagnostic: (String) -> Unit = {},
) {
    fun isEnrolled(): Boolean = bioFile.isFile

    fun encryptCipherOrNull(): Cipher? =
        runCatching {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, obtainKey()) }
        }.onFailure { error ->
            recordDiagnostic("bio enrol cipher unavailable: ${error.message ?: error.javaClass.simpleName}")
        }.getOrNull()

    fun store(
        masterKey: ByteArray,
        authorizedCipher: Cipher,
    ): Boolean =
        runCatching {
            val ciphertext = authorizedCipher.doFinal(masterKey)
            val iv = authorizedCipher.iv
            val payload =
                ByteBuffer
                    .allocate(1 + 1 + iv.size + ciphertext.size)
                    .put(FORMAT_VERSION)
                    .put(iv.size.toByte())
                    .put(iv)
                    .put(ciphertext)
                    .array()
            AtomicFileWrites.writeBytesAtomic(bioFile, payload)
            true
        }.onFailure { error ->
            recordDiagnostic("bio keybox store failed: ${error.message ?: error.javaClass.simpleName}")
        }.getOrDefault(false)

    @Suppress("SwallowedException")
    fun decryptCipherOrNull(): Cipher? {
        val stored = readPayloadOrNull() ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, stored.iv))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            recordDiagnostic("bio keybox invalidated by enrolment change; cleared")
            clear()
            null
        } catch (error: Exception) {
            recordDiagnostic("bio unlock cipher unavailable: ${error.message ?: error.javaClass.simpleName}")
            null
        }
    }

    fun unsealMasterKeyOrNull(authorizedCipher: Cipher): ByteArray? {
        val stored = readPayloadOrNull() ?: return null
        return runCatching { authorizedCipher.doFinal(stored.ciphertext) }
            .onFailure { error ->
                recordDiagnostic("bio keybox unseal failed: ${error.message ?: error.javaClass.simpleName}")
            }.getOrNull()
    }

    fun clear() {
        bioFile.delete()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private class Payload(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    private fun readPayloadOrNull(): Payload? =
        runCatching {
            val raw = bioFile.readBytes()
            val buffer = ByteBuffer.wrap(raw)
            check(buffer.get() == FORMAT_VERSION) { "unsupported bio blob version" }
            val ivSize = buffer.get().toInt()
            check(ivSize in 1..MAX_IV_BYTES) { "malformed bio blob iv" }
            val iv = ByteArray(ivSize).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            check(ciphertext.isNotEmpty()) { "empty bio blob" }
            Payload(iv = iv, ciphertext = ciphertext)
        }.getOrNull()

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val builder =
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(builder.build()) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "foxhole.keybox.bio"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
        const val FORMAT_VERSION: Byte = 1
        const val MAX_IV_BYTES = 32
    }
}
