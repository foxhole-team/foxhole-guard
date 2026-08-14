@file:Suppress("DEPRECATION")

package com.foxhole.guard.core.sentinel

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File

internal fun AndroidKeystoreFileCipher.readBytesMigratingLegacy(
    context: Context,
    file: File,
): ByteArray =
    runCatching { readBytes(file) }
        .getOrElse { currentError ->
            LegacyEncryptedFileCompat.readBytesOrNull(context, file)?.also { legacyBytes ->
                writeBytesAtomic(file, legacyBytes)
            } ?: throw currentError
        }

internal object LegacyEncryptedFileCompat {
    fun readBytesOrNull(
        context: Context,
        file: File,
    ): ByteArray? {
        if (!file.exists()) {
            return null
        }
        val appContext = context.applicationContext
        return runCatching {
            val masterKey =
                MasterKey
                    .Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedFile
                .Builder(
                    appContext,
                    file,
                    masterKey,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
                ).build()
                .openFileInput()
                .use { it.readBytes() }
        }.getOrNull()
    }
}
