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

    suspend fun deleteOrphans(activeSecretRefs: Set<String>): Int = 0

    suspend fun deleteAll(): Int = 0
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

    override suspend fun deleteOrphans(activeSecretRefs: Set<String>): Int =
        withContext(Dispatchers.IO) {
            deleteOrphanProfileSecretFiles(baseDir, activeSecretRefs)
        }

    override suspend fun deleteAll(): Int =
        withContext(Dispatchers.IO) {
            deleteProfileSecretFiles(baseDir)
        }

    private fun fileFor(secretRef: String): File = profileSecretFileFor(baseDir, secretRef)
}

internal fun profileSecretFileFor(
    baseDir: File,
    secretRef: String,
): File {
    require(isValidProfileSecretRef(secretRef)) { "invalid profile secret ref" }
    return File(baseDir, "$secretRef.json")
}

internal fun deleteOrphanProfileSecretFiles(
    baseDir: File,
    activeSecretRefs: Set<String>,
): Int {
    val activeRefs = activeSecretRefs.filter(::isValidProfileSecretRef).toSet()
    val files =
        baseDir.listFiles { file ->
            file.isFile && file.name.endsWith(PROFILE_SECRET_FILE_SUFFIX)
        }.orEmpty()
    return files.count { file ->
        val secretRef = file.name.removeSuffix(PROFILE_SECRET_FILE_SUFFIX)
        secretRef !in activeRefs && file.delete()
    }
}

internal fun deleteProfileSecretFiles(baseDir: File): Int {
    val files =
        baseDir.listFiles { file ->
            file.isFile && file.name.endsWith(PROFILE_SECRET_FILE_SUFFIX)
        }.orEmpty()
    return files.count(File::delete)
}

internal fun isValidProfileSecretRef(secretRef: String): Boolean =
    PROFILE_SECRET_REF_PATTERN.matches(secretRef)

private const val PROFILE_SECRET_FILE_SUFFIX = ".json"

private val PROFILE_SECRET_REF_PATTERN =
    Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )
