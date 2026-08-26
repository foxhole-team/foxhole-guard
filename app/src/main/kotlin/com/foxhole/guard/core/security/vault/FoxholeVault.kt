package com.foxhole.guard.core.security.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class FoxholeVault(
    context: Context,
) {
    enum class Section(internal val dirName: String, internal val keyAlias: String) {
        JOURNALS("journals", "fhg_vault_journals"),
        CONFIGS("configs", "fhg_vault_configs"),
        AUTH("auth", "fhg_vault_auth"),
    }

    private val root = File(context.applicationContext.filesDir, VAULT_DIR)
    private val lock = Any()

    fun put(section: Section, key: String, value: ByteArray) {
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, sectionKey(section))
            val encrypted = cipher.doFinal(value)
            val file = fileFor(section, key)
            file.parentFile?.mkdirs()
            file.writeBytes(cipher.iv + encrypted)
        }
    }

    fun get(section: Section, key: String): ByteArray? {
        synchronized(lock) {
            val file = fileFor(section, key)
            if (!file.exists()) {
                return null
            }
            return runCatching {
                val payload = file.readBytes()
                require(payload.size > GCM_IV_BYTES) { "vault payload too short" }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    sectionKey(section),
                    GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES),
                )
                cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES)
            }.getOrNull()
        }
    }

    fun delete(section: Section, key: String) {
        synchronized(lock) {
            fileFor(section, key).delete()
        }
    }

    fun list(section: Section): List<String> {
        synchronized(lock) {
            return sectionDir(section)
                .listFiles()
                .orEmpty()
                .mapNotNull { file -> decodeVaultFileName(file.name) }
        }
    }

    fun wipe(section: Section) {
        synchronized(lock) {
            sectionDir(section).deleteRecursively()
        }
    }

    private fun sectionDir(section: Section): File = File(root, section.dirName)

    private fun fileFor(section: Section, key: String): File =
        File(sectionDir(section), encodeVaultFileName(key))

    private fun sectionKey(section: Section): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(section.keyAlias, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                section.keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(VAULT_KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val VAULT_DIR = "vault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val VAULT_KEY_BITS = 256
    }
}

internal fun encodeVaultFileName(key: String): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray())

internal fun decodeVaultFileName(fileName: String): String? =
    runCatching { String(java.util.Base64.getUrlDecoder().decode(fileName)) }.getOrNull()
