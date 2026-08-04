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

/**
 * The application vault, split into isolated sections: each has its own AES-GCM key in the Android
 * Keystore and its own directory, so compromising or clearing one section does not touch the rest.
 * There is deliberately no shared master key.
 *
 * Sections:
 *  - [Section.AUTH] holds encrypted web-app credentials ([WebAppCredentialsStore]);
 *  - [Section.JOURNALS] / [Section.CONFIGS] are reserved for migrating the existing isolated
 *    stores; the facade gives them one entry point without duplicating encryption.
 *
 * File format: a 12-byte IV followed by ciphertext and tag. The file name is the base64url of the
 * key without padding, so arbitrary keys are filesystem-safe.
 */
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

    /** Wipes one section only; isolation is the vault's contract. */
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

/** File name from an arbitrary key: base64url without padding — reversible and FS-safe. */
internal fun encodeVaultFileName(key: String): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray())

internal fun decodeVaultFileName(fileName: String): String? =
    runCatching { String(java.util.Base64.getUrlDecoder().decode(fileName)) }.getOrNull()
