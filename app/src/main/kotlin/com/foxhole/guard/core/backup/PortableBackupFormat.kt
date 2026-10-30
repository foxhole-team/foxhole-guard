package com.foxhole.guard.core.backup

import com.foxhole.guard.core.security.GuardCrypto
import com.foxhole.guard.core.security.GuardKdfParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Base64

internal sealed interface PreparedBackupImport {
    data class Plain(val document: BackupDocument) : PreparedBackupImport

    class Encrypted internal constructor(private val payload: String) : PreparedBackupImport {
        fun unlock(password: CharArray, crypto: GuardCrypto): BackupParseResult =
            decryptBackupDocument(payload, password, crypto)
    }
}

internal sealed interface BackupImportPreparation {
    data class Ready(val prepared: PreparedBackupImport) : BackupImportPreparation
    data class Failure(val reason: BackupParseResult.Failure) : BackupImportPreparation
}

internal suspend fun preparePortableBackupImportOnce(
    read: suspend () -> String?,
): BackupImportPreparation {
    val payload = read() ?: return BackupImportPreparation.Failure(BackupParseResult.Failure.MALFORMED)
    return withContext(Dispatchers.Default) { preparePortableBackupPayload(payload) }
}

internal fun preparePortableBackupPayload(payload: String): BackupImportPreparation {
    val bytes = payload.toByteArray(Charsets.UTF_8)
    val size = bytes.size
    bytes.fill(0)
    if (size > MAX_ENCRYPTED_BACKUP_FILE_BYTES) return failure(BackupParseResult.Failure.MALFORMED)
    val root = parsePortableRoot(payload) ?: return failure(BackupParseResult.Failure.MALFORMED)
    val format = root.string("format") ?: return failure(BackupParseResult.Failure.MALFORMED)
    return when (format) {
        BACKUP_DOCUMENT_FORMAT -> preparePlain(payload, root, size)
        ENCRYPTED_BACKUP_FORMAT -> prepareEncrypted(payload, root)
        else -> failure(BackupParseResult.Failure.NOT_A_BACKUP)
    }
}

private fun parsePortableRoot(payload: String): JsonObject? =
    try {
        backupJson.parseToJsonElement(payload).jsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun preparePlain(
    payload: String,
    root: JsonObject,
    size: Int,
): BackupImportPreparation {
    if (size > MAX_BACKUP_PLAINTEXT_BYTES || ENCRYPTED_FIELDS.any(root::containsKey)) {
        return failure(BackupParseResult.Failure.MALFORMED)
    }
    return when (val decoded = decodeBackupDocument(payload)) {
        is BackupParseResult.Success -> BackupImportPreparation.Ready(PreparedBackupImport.Plain(decoded.document))
        is BackupParseResult.Failure -> failure(decoded)
    }
}

private fun prepareEncrypted(payload: String, root: JsonObject): BackupImportPreparation {
    val version = (root["formatVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
        ?: return failure(BackupParseResult.Failure.MALFORMED)
    if (version != ENCRYPTED_BACKUP_FORMAT_VERSION) {
        return failure(BackupParseResult.Failure.UNSUPPORTED_FORMAT_VERSION)
    }
    val kdf = root.string("kdf") ?: return failure(BackupParseResult.Failure.MALFORMED)
    val params = root["kdfParams"] as? JsonObject ?: return failure(BackupParseResult.Failure.MALFORMED)
    val kdfParams = params.kdfParamsOrNull() ?: return failure(BackupParseResult.Failure.MALFORMED)
    if (!isSupportedBackupKdf(kdf, kdfParams)) return failure(BackupParseResult.Failure.UNSUPPORTED_ENCRYPTION)
    val salt = root.string("salt") ?: return failure(BackupParseResult.Failure.MALFORMED)
    val nonce = root.string("nonce") ?: return failure(BackupParseResult.Failure.MALFORMED)
    val ciphertext = root.string("ciphertext") ?: return failure(BackupParseResult.Failure.MALFORMED)
    if (!smallBase64HasSize(salt, GuardCrypto.SALT_BYTES) || !smallBase64HasSize(nonce, PORTABLE_NONCE_BYTES) ||
        !ciphertext.hasBoundedBase64Syntax()
    ) {
        return failure(BackupParseResult.Failure.MALFORMED)
    }
    return BackupImportPreparation.Ready(PreparedBackupImport.Encrypted(payload))
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull

private fun JsonObject.positiveInt(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

private fun JsonObject.kdfParamsOrNull(): GuardKdfParams? {
    val memory = positiveInt("memKib") ?: return null
    val operations = positiveInt("ops") ?: return null
    val parallelism = positiveInt("parallelism") ?: return null
    return GuardKdfParams(memory, operations, parallelism)
}

private fun smallBase64HasSize(encoded: String, size: Int): Boolean {
    if (encoded.length > 64) return false
    val decoded =
        try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return false
        }
    return try {
        decoded.size == size
    } finally {
        decoded.fill(0)
    }
}

private fun String.hasBoundedBase64Syntax(): Boolean {
    val padding = when {
        endsWith("==") -> 2
        endsWith('=') -> 1
        else -> 0
    }
    val contentLength = length - padding
    if (contentLength <= 0 || length % 4 == 1) return false
    if (padding > 0 && length % 4 != 0) return false
    if ((0 until contentLength).any { index -> !this[index].isBase64Character() }) {
        return false
    }
    val remainder = length % 4
    val decodedBytes = length.toLong() / 4L * 3L - padding + when (remainder) {
        2 -> 1
        3 -> 2
        else -> 0
    }
    return decodedBytes in PORTABLE_TAG_BYTES.toLong()..MAX_BACKUP_CIPHERTEXT_BYTES.toLong()
}

private fun Char.isBase64Character(): Boolean =
    when (this) {
        in 'A'..'Z', in 'a'..'z', in '0'..'9', '+', '/' -> true
        else -> false
    }

private fun failure(reason: BackupParseResult.Failure) = BackupImportPreparation.Failure(reason)

private const val PORTABLE_NONCE_BYTES = 12
private const val PORTABLE_TAG_BYTES = 16
private val ENCRYPTED_FIELDS = setOf("kdf", "kdfParams", "salt", "nonce", "ciphertext")
