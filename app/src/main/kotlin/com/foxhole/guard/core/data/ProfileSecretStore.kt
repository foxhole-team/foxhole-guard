package com.foxhole.guard.core.data

import android.content.Context
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.core.model.migrateStoredProtocolToken
import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import com.foxhole.guard.core.sentinel.readBytesMigratingLegacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
            val storedPayload =
                fileCipher.readBytesMigratingLegacy(appContext, file).decodeToString()
            val migratedPayload = migrateStoredProfileSecretPayload(storedPayload, json)
            val decoded =
                json.decodeFromString(
                    StoredProfileSecret.serializer(),
                    migratedPayload,
                )
            if (migratedPayload != storedPayload) {
                fileCipher.writeBytesAtomic(file, migratedPayload.encodeToByteArray())
            }
            decoded
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

internal fun migrateStoredProfileSecretPayload(
    payload: String,
    json: Json,
): String {
    val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return payload
    val options = root["protocolOptions"] as? JsonArray ?: return payload
    var changed = false
    val migratedOptions =
        JsonArray(
            options.map { option ->
                val objectValue = option as? JsonObject ?: return@map option
                val raw = (objectValue["protocolHint"] as? JsonPrimitive)?.contentOrNull ?: return@map option
                val migrated = migrateStoredProtocolToken(raw)
                if (migrated == raw) {
                    objectValue
                } else {
                    changed = true
                    JsonObject(
                        objectValue.mapValues { (key, value) ->
                            if (key == "protocolHint") JsonPrimitive(migrated) else value
                        },
                    )
                }
            },
        )
    if (!changed) {
        return payload
    }
    val migratedRoot =
        JsonObject(
            root.mapValues { (key, value) ->
                if (key == "protocolOptions") migratedOptions else value
            },
        )
    return json.encodeToString(JsonObject.serializer(), migratedRoot)
}
