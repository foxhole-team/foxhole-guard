package com.foxhole.beta.core.data

import android.content.Context
import com.foxhole.beta.core.model.StoredProfileSecret
import com.foxhole.beta.core.security.AndroidKeystoreFileCipher
import com.foxhole.beta.core.security.readBytesMigratingLegacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

interface ProfileSecretStore {
    suspend fun write(secretRef: String, value: StoredProfileSecret)

    suspend fun read(secretRef: String): StoredProfileSecret?

    suspend fun delete(secretRef: String): Boolean
}

class EncryptedProfileSecretStore(
    context: Context,
    private val json: Json,
) : ProfileSecretStore {
    private val appContext = context.applicationContext
    private val baseDir = File(context.filesDir, "profile-secrets").apply { mkdirs() }
    private val fileCipher = AndroidKeystoreFileCipher("foxhole.profile.secrets")

    override suspend fun write(secretRef: String, value: StoredProfileSecret) {
        withContext(Dispatchers.IO) {
            val file = fileFor(secretRef)
            fileCipher.writeBytesAtomic(
                file,
                json.encodeToString(StoredProfileSecret.serializer(), value).encodeToByteArray(),
            )
        }
    }

    override suspend fun read(secretRef: String): StoredProfileSecret? =
        withContext(Dispatchers.IO) {
            val file = fileFor(secretRef)
            if (!file.exists()) {
                return@withContext null
            }
            json.decodeFromString(
                StoredProfileSecret.serializer(),
                fileCipher.readBytesMigratingLegacy(appContext, file).decodeToString(),
            )
        }

    override suspend fun delete(secretRef: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = fileFor(secretRef)
            !file.exists() || file.delete()
        }

    private fun fileFor(secretRef: String): File = File(baseDir, "$secretRef.json")
}
