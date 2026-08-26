package com.foxhole.guard.core.backup

import com.foxhole.guard.core.security.GuardCrypto
import com.foxhole.guard.core.security.GuardKdfParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val ENCRYPTED_BACKUP_FORMAT = "foxhole-guard-encrypted-backup"
internal const val ENCRYPTED_BACKUP_FORMAT_VERSION = 1
internal const val BACKUP_PASSWORD_MIN_CHARS = 10

private const val BACKUP_KDF_NAME = "argon2id13"
private const val BACKUP_KDF_MEM_KIB = 64 * 1024
private const val BACKUP_KDF_OPS = 3
private const val BACKUP_KDF_PARALLELISM = 1
private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
private const val AES_GCM_TAG_BITS = 128
private const val AES_GCM_TAG_BYTES = AES_GCM_TAG_BITS / Byte.SIZE_BITS
private const val AES_NONCE_BYTES = 12
internal const val MAX_BACKUP_CIPHERTEXT_BYTES = 20 * 1024 * 1024
internal const val MAX_BACKUP_PLAINTEXT_BYTES = MAX_BACKUP_CIPHERTEXT_BYTES - AES_GCM_TAG_BYTES
internal const val MAX_ENCRYPTED_BACKUP_FILE_BYTES = 28 * 1024 * 1024

@Serializable
private data class EncryptedBackupEnvelope(
    val format: String = ENCRYPTED_BACKUP_FORMAT,
    val formatVersion: Int = ENCRYPTED_BACKUP_FORMAT_VERSION,
    val kdf: String = BACKUP_KDF_NAME,
    val kdfParams: GuardKdfParams = backupKdfParams(),
    val salt: String,
    val nonce: String,
    val ciphertext: String,
)

internal fun encryptBackupDocument(
    document: BackupDocument,
    password: CharArray,
    crypto: GuardCrypto,
    random: SecureRandom = SecureRandom(),
): String {
    require(password.size >= BACKUP_PASSWORD_MIN_CHARS) { "backup password is too short" }
    val salt = ByteArray(GuardCrypto.SALT_BYTES).also(random::nextBytes)
    val nonce = ByteArray(AES_NONCE_BYTES).also(random::nextBytes)
    val passwordBytes = password.toUtf8Bytes()
    val plainBytes = encodeBackupDocument(document).toByteArray(StandardCharsets.UTF_8)
    var key = ByteArray(0)
    return try {
        requirePortableBackupPlaintextSize(plainBytes.size)
        key = crypto.argon2id(passwordBytes, salt, backupKdfParams())
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AES_GCM_TAG_BITS, nonce))
        cipher.updateAAD(backupAad())
        val encrypted = cipher.doFinal(plainBytes)
        val envelope =
            EncryptedBackupEnvelope(
                salt = salt.encode64(),
                nonce = nonce.encode64(),
                ciphertext = encrypted.encode64(),
            )
        backupJson.encodeToString(EncryptedBackupEnvelope.serializer(), envelope) + "\n"
    } finally {
        passwordBytes.fill(0)
        plainBytes.fill(0)
        key.fill(0)
        salt.fill(0)
        nonce.fill(0)
    }
}

@Suppress("ReturnCount") // Guard-clause parser: never derive a key until every envelope bound is validated.
internal fun decryptBackupDocument(
    payload: String,
    password: CharArray,
    crypto: GuardCrypto,
): BackupParseResult {
    val format =
        runCatching {
            backupJson.parseToJsonElement(payload).jsonObject["format"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: return BackupParseResult.Failure.MALFORMED

    if (format == BACKUP_DOCUMENT_FORMAT) return decodeBackupDocument(payload)
    if (format != ENCRYPTED_BACKUP_FORMAT) return BackupParseResult.Failure.NOT_A_BACKUP

    val envelope =
        try {
            backupJson.decodeFromString(EncryptedBackupEnvelope.serializer(), payload)
        } catch (_: SerializationException) {
            return BackupParseResult.Failure.MALFORMED
        } catch (_: IllegalArgumentException) {
            return BackupParseResult.Failure.MALFORMED
        }
    if (envelope.formatVersion != ENCRYPTED_BACKUP_FORMAT_VERSION) {
        return BackupParseResult.Failure.UNSUPPORTED_FORMAT_VERSION
    }
    if (envelope.kdf != BACKUP_KDF_NAME || envelope.kdfParams != backupKdfParams()) {
        return BackupParseResult.Failure.UNSUPPORTED_ENCRYPTION
    }
    val salt = envelope.salt.decode64OrNull()?.takeIf { it.size == GuardCrypto.SALT_BYTES }
        ?: return BackupParseResult.Failure.MALFORMED
    val nonce = envelope.nonce.decode64OrNull()?.takeIf { it.size == AES_NONCE_BYTES }
        ?: return BackupParseResult.Failure.MALFORMED
    val ciphertext = envelope.ciphertext.decode64OrNull()
        ?.takeIf { it.size in 1..MAX_BACKUP_CIPHERTEXT_BYTES }
        ?: return BackupParseResult.Failure.MALFORMED
    val passwordBytes = password.toUtf8Bytes()
    var key = ByteArray(0)
    var plainBytes = ByteArray(0)
    return try {
        key = crypto.argon2id(passwordBytes, salt, envelope.kdfParams)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AES_GCM_TAG_BITS, nonce))
        cipher.updateAAD(backupAad())
        plainBytes = cipher.doFinal(ciphertext)
        decodeBackupDocument(plainBytes.toString(StandardCharsets.UTF_8))
    } catch (_: GeneralSecurityException) {
        BackupParseResult.Failure.WRONG_PASSWORD_OR_CORRUPT
    } catch (_: IllegalStateException) {
        BackupParseResult.Failure.WRONG_PASSWORD_OR_CORRUPT
    } finally {
        passwordBytes.fill(0)
        key.fill(0)
        plainBytes.fill(0)
        salt.fill(0)
        nonce.fill(0)
        ciphertext.fill(0)
    }
}

private fun backupKdfParams(): GuardKdfParams =
    GuardKdfParams(
        memKib = BACKUP_KDF_MEM_KIB,
        ops = BACKUP_KDF_OPS,
        parallelism = BACKUP_KDF_PARALLELISM,
    )

internal fun requirePortableBackupPlaintextSize(size: Int) {
    require(size in 0..MAX_BACKUP_PLAINTEXT_BYTES) { "backup payload is too large" }
}

private fun backupAad(): ByteArray =
    buildString {
        append(ENCRYPTED_BACKUP_FORMAT)
        append('|')
        append(ENCRYPTED_BACKUP_FORMAT_VERSION)
        append('|')
        append(BACKUP_KDF_NAME)
        append('|')
        append(BACKUP_KDF_MEM_KIB)
        append('|')
        append(BACKUP_KDF_OPS)
        append('|')
        append(BACKUP_KDF_PARALLELISM)
    }.toByteArray(StandardCharsets.UTF_8)

private fun CharArray.toUtf8Bytes(): ByteArray {
    val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(this))
    return ByteArray(buffer.remaining()).also(buffer::get).also {
        if (buffer.hasArray()) buffer.array().fill(0)
    }
}

private fun ByteArray.encode64(): String = Base64.getEncoder().encodeToString(this)

private fun String.decode64OrNull(): ByteArray? =
    runCatching { Base64.getDecoder().decode(this) }.getOrNull()
