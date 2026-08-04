package com.foxhole.guard.core.security

import android.content.Context
import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import com.foxhole.guard.core.sentinel.FileCipher
import com.foxhole.guard.core.sentinel.readBytesMigratingLegacy
import java.io.File
import java.security.SecureRandom

/** Raised when an existing file-share vault key cannot be recovered safely. */
class ShareVaultKeyUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Supplies the root key for the encrypted file-share vault.
 *
 * The wrapped key lives under no-backup storage, outside the vault it unlocks. An unreadable or
 * malformed existing key is never replaced: silently re-keying would make old ciphertext
 * unrecoverable while making the failure look like a fresh empty vault.
 */
class ShareVaultKeySource internal constructor(
    private val keyFile: File,
    private val cipher: FileCipher,
    private val readMigrating: (File) -> ByteArray,
    private val randomKey: () -> ByteArray,
) {
    constructor(context: Context) : this(
        appContext = context.applicationContext,
        androidCipher = AndroidKeystoreFileCipher(KEYSTORE_ALIAS),
    )

    private constructor(
        appContext: Context,
        androidCipher: AndroidKeystoreFileCipher,
    ) : this(
        keyFile = File(appContext.noBackupFilesDir, KEY_PATH),
        cipher = androidCipher,
        readMigrating = { file -> androidCipher.readBytesMigratingLegacy(appContext, file) },
        randomKey = { ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes) },
    )

    @Synchronized
    fun acquireKey(): ByteArray {
        keyFile.parentFile?.mkdirs()
        return if (keyFile.exists()) readExistingKey() else createKey()
    }

    private fun readExistingKey(): ByteArray {
        val existing =
            try {
                readMigrating(keyFile)
            } catch (error: Exception) {
                throw ShareVaultKeyUnavailableException("existing file-share vault key is unavailable", error)
            }
        if (existing.size != KEY_BYTES) {
            existing.fill(0)
            throw ShareVaultKeyUnavailableException("existing file-share vault key has an invalid length")
        }
        return existing
    }

    private fun createKey(): ByteArray {
        val created = randomKey()
        if (created.size != KEY_BYTES) {
            created.fill(0)
            throw ShareVaultKeyUnavailableException("file-share vault key generation failed")
        }
        try {
            cipher.writeBytesAtomic(keyFile, created)
            return created
        } catch (error: Exception) {
            created.fill(0)
            throw ShareVaultKeyUnavailableException("file-share vault key persistence failed", error)
        }
    }

    private companion object {
        const val KEY_PATH = "keys/file-share-vault.key"
        const val KEYSTORE_ALIAS = "foxhole.file.share.vault"
        const val KEY_BYTES = 32
    }
}
