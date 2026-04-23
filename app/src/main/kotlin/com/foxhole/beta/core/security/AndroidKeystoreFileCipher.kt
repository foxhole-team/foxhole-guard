package com.foxhole.beta.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidKeystoreFileCipher(
    private val keyAlias: String,
) {
    fun readBytes(file: File): ByteArray {
        val envelope = AesGcmFileEnvelope.decode(file.readBytes())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, envelope.iv),
        )
        return cipher.doFinal(envelope.ciphertext)
    }

    fun writeBytesAtomic(
        file: File,
        plaintext: ByteArray,
    ) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        writeBytes(tempFile, plaintext)
        if (file.exists()) {
            file.delete()
        }
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
    }

    fun writeBytes(
        file: File,
        plaintext: ByteArray,
    ) {
        file.parentFile?.mkdirs()
        file.delete()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload =
            AesGcmFileEnvelope.encode(
                iv = cipher.iv,
                ciphertext = cipher.doFinal(plaintext),
            )
        file.outputStream().use { stream ->
            stream.write(payload)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
        keyGenerator.init(
            KeyGenParameterSpec
                .Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
