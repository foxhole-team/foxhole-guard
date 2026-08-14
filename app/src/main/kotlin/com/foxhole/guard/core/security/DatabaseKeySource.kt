package com.foxhole.guard.core.security

import android.content.Context
import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import com.foxhole.guard.core.sentinel.readBytesMigratingLegacy
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom

/** Thrown when the SQLCipher passphrase cannot be provided without a password unlock. */
class DatabaseKeyUnavailableException(
    message: String,
) : IllegalStateException(message)

/** Provides the 32-byte SQLCipher passphrase (a.k.a. dataKey). */
interface DatabaseKeySource {
    fun acquirePassphrase(): ByteArray
}

/**
 * Default (level 1) key source: a random passphrase file wrapped by the Android
 * Keystore. Hardened against the silent-rekey bricking risk - it refuses to mint a
 * fresh passphrase whenever a password keybox exists, so any pre-unlock DB touch
 * fails loudly instead of replacing the key of an existing encrypted database.
 */
class KeystoreDatabaseKeySource internal constructor(
    private val passphraseFile: File,
    private val cipher: com.foxhole.guard.core.sentinel.FileCipher,
    private val readMigrating: (File) -> ByteArray,
    private val keyboxExists: () -> Boolean,
    private val deleteKeystoreAlias: () -> Unit,
) : DatabaseKeySource {
    override fun acquirePassphrase(): ByteArray {
        passphraseFile.parentFile?.mkdirs()
        if (passphraseFile.exists()) {
            return readMigrating(passphraseFile)
        }
        if (keyboxExists()) {
            throw DatabaseKeyUnavailableException("passphrase migrated into keybox; password unlock required")
        }
        val created = ByteArray(DATA_KEY_BYTES).also(SecureRandom()::nextBytes)
        cipher.writeBytesAtomic(passphraseFile, created)
        return created
    }

    fun readExistingOrNull(): ByteArray? = if (passphraseFile.exists()) readMigrating(passphraseFile) else null

    fun writeBack(passphrase: ByteArray) {
        passphraseFile.parentFile?.mkdirs()
        cipher.writeBytesAtomic(passphraseFile, passphrase)
    }

    fun exists(): Boolean = passphraseFile.exists()

    fun delete() {
        passphraseFile.delete()
        deleteKeystoreAlias()
    }
}

/** Production factory mirroring a constructor; keeps the class's DI-friendly ctor injectable. */
@Suppress("FunctionName")
fun KeystoreDatabaseKeySource(
    context: Context,
    keyboxExists: () -> Boolean,
): KeystoreDatabaseKeySource {
    val appContext = context.applicationContext
    val cipher = AndroidKeystoreFileCipher(KEYSTORE_ALIAS)
    return KeystoreDatabaseKeySource(
        passphraseFile = File(appContext.filesDir, PASSPHRASE_PATH),
        cipher = cipher,
        readMigrating = { file -> cipher.readBytesMigratingLegacy(appContext, file) },
        keyboxExists = keyboxExists,
        deleteKeystoreAlias = {
            runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                    keyStore.deleteEntry(KEYSTORE_ALIAS)
                }
            }
        },
    )
}

private const val PASSPHRASE_PATH = "keys/profile-db.passphrase"
private const val KEYSTORE_ALIAS = "foxhole.profile.db.passphrase"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val DATA_KEY_BYTES = 32

/**
 * Level 3 key source: the passphrase lives only in the password-derived keybox and is
 * held in the session holder while unlocked. Fails closed if reached before unlock;
 * every pre-unlock DB path is gated so that never happens in practice.
 */
class KeyboxDatabaseKeySource(
    private val session: SecureSessionHolder,
) : DatabaseKeySource {
    override fun acquirePassphrase(): ByteArray =
        session.copyDataKeyOrNull()
            ?: throw DatabaseKeyUnavailableException("data key unavailable; password unlock required")
}
