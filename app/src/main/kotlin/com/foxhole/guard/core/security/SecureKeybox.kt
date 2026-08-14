package com.foxhole.guard.core.security

import com.foxhole.guard.core.sentinel.FileCipher
import kotlinx.serialization.json.Json
import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

internal sealed interface KeyboxUnlockOutcome {
    class Success(
        val session: UnlockedKeyboxSession,
        // Failures recorded since the previous successful unlock (read before the
        // reset), so the caller can surface "N failed attempts while you were away".
        val priorFailedAttempts: Int = 0,
        val priorLastFailedAt: Long = 0L,
    ) : KeyboxUnlockOutcome

    class WrongPassword(
        val failedAttempts: Int,
        val nextDelayMs: Long,
    ) : KeyboxUnlockOutcome

    class Corrupted(
        val reason: String,
    ) : KeyboxUnlockOutcome

    data object Missing : KeyboxUnlockOutcome
}

/**
 * Envelope keybox from task_new.md: Argon2id(password) -> masterKey wraps the random
 * dataKey (the SQLCipher passphrase) plus the guard journal's X25519 private key.
 * The X25519 public key stays plaintext so journal writers can seal entries while
 * the app is locked; reading them back requires the password.
 */
@Suppress("TooManyFunctions")
internal class SecureKeybox(
    private val keyboxFile: File,
    private val attemptsFile: File,
    private val attemptsCipher: FileCipher,
    private val keyboxCipher: FileCipher,
    private val crypto: GuardCrypto,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    fun exists(): Boolean = keyboxFile.isFile

    fun guardPublicKeyOrNull(): ByteArray? =
        runCatching { decode64(readDocument().guardPublicKey) }.getOrNull()

    fun checkpointOrNull(): KeyboxCheckpoint? = runCatching { readDocument().checkpoint }.getOrNull()

    /** [KeyboxDocument.CREDENTIAL_PASSWORD] or [KeyboxDocument.CREDENTIAL_PIN]; null without a box. */
    fun credentialKindOrNull(): String? = runCatching { readDocument().credential }.getOrNull()

    fun create(
        password: ByteArray,
        dataKey: ByteArray,
        kdf: GuardKdfParams,
        credential: String = KeyboxDocument.CREDENTIAL_PIN,
    ): UnlockedKeyboxSession {
        require(dataKey.size == DATA_KEY_BYTES) { "dataKey must be $DATA_KEY_BYTES bytes" }
        val keypair = crypto.sealedBoxKeypair()
        val salt = randomBytes(GuardCrypto.SALT_BYTES)
        val masterKey = crypto.argon2id(password, salt, kdf)
        val ownedDataKey = dataKey.copyOf()
        val document =
            buildDocument(
                masterKey = masterKey,
                salt = salt,
                kdf = kdf,
                dataKey = ownedDataKey,
                guardPrivateKey = keypair.privateKey,
                guardPublicKey = keypair.publicKey,
                createdAtWallClock = wallClock(),
                checkpoint = KeyboxCheckpoint(),
                credential = credential,
            )
        writeDocument(document)
        resetAttempts()
        return UnlockedKeyboxSession(
            keybox = this,
            masterKey = masterKey,
            dataKey = ownedDataKey,
            guardPrivateKey = keypair.privateKey,
            guardPublicKey = keypair.publicKey,
            document = document,
        )
    }

    fun unlock(password: ByteArray): KeyboxUnlockOutcome {
        if (!exists()) {
            return KeyboxUnlockOutcome.Missing
        }
        val document =
            runCatching { readDocument() }.getOrElse {
                return KeyboxUnlockOutcome.Corrupted("unreadable keybox: ${it.message}")
            }
        return unlockDocument(password, document)
    }

    /**
     * Opens the box with an already-derived master key (the biometric path: the key
     * comes out of the hardware-bound `keybox.bio` blob, no Argon2 run). A verifier
     * mismatch means the blob is stale (PIN changed underneath it) — reported as
     * Corrupted for the caller to silently fall back to the PIN, never as a failed
     * attempt.
     */
    fun unlockWithMasterKey(masterKey: ByteArray): KeyboxUnlockOutcome {
        // This method owns [masterKey] and its KDoc promises it is zeroed on every failure, so the
        // early Missing / unreadable exits must wipe it too — not just the verifier-mismatch and
        // GCM-failure branches below — or the hardware-unsealed key lingers in the heap.
        if (!exists()) {
            masterKey.zeroize()
            return KeyboxUnlockOutcome.Missing
        }
        val document =
            runCatching { readDocument() }.getOrElse {
                masterKey.zeroize()
                return KeyboxUnlockOutcome.Corrupted("unreadable keybox: ${it.message}")
            }
        val storedVerifier = runCatching { decode64(document.verifier) }.getOrNull()
        if (storedVerifier == null || !MessageDigest.isEqual(verifierFor(masterKey), storedVerifier)) {
            masterKey.zeroize()
            return KeyboxUnlockOutcome.Corrupted("stale biometric master key")
        }
        return openSecrets(masterKey, document)
    }

    /**
     * Confirms a credential WITHOUT opening the box or touching the attempt counter:
     * the action gate re-asks for the PIN while the app is already unlocked, so a
     * mistyped confirmation must not arm the unlock backoff.
     */
    fun verifyPassword(password: ByteArray): Boolean {
        if (!exists()) {
            return false
        }
        val document = runCatching { readDocument() }.getOrNull() ?: return false
        val salt = runCatching { decode64(document.salt) }.getOrNull() ?: return false
        val masterKey = crypto.argon2id(password, salt, document.kdf)
        val storedVerifier = runCatching { decode64(document.verifier) }.getOrNull()
        val matches = storedVerifier != null && MessageDigest.isEqual(verifierFor(masterKey), storedVerifier)
        masterKey.zeroize()
        return matches
    }

    private fun unlockDocument(
        password: ByteArray,
        document: KeyboxDocument,
    ): KeyboxUnlockOutcome {
        val salt =
            runCatching { decode64(document.salt) }.getOrElse {
                return KeyboxUnlockOutcome.Corrupted("keybox salt is malformed")
            }
        val masterKey = crypto.argon2id(password, salt, document.kdf)
        val storedVerifier = runCatching { decode64(document.verifier) }.getOrNull()
        if (storedVerifier == null || !MessageDigest.isEqual(verifierFor(masterKey), storedVerifier)) {
            masterKey.zeroize()
            val attempts = recordFailedAttempt()
            return KeyboxUnlockOutcome.WrongPassword(
                failedAttempts = attempts.failedAttempts,
                nextDelayMs = backoffDelayMs(attempts.failedAttempts),
            )
        }
        return openSecrets(masterKey, document)
    }

    private fun openSecrets(
        masterKey: ByteArray,
        document: KeyboxDocument,
    ): KeyboxUnlockOutcome {
        val secrets =
            try {
                gcmDecrypt(
                    key = masterKey,
                    nonce = decode64(document.nonce),
                    ciphertext = decode64(document.wrappedSecrets),
                    aad = canonicalAad(document),
                )
            } catch (_: GeneralSecurityException) {
                masterKey.zeroize()
                return KeyboxUnlockOutcome.Corrupted(
                    "keybox integrity check failed (metadata tampered or file damaged)",
                )
            } catch (_: IllegalArgumentException) {
                masterKey.zeroize()
                return KeyboxUnlockOutcome.Corrupted("keybox payload is malformed")
            }
        if (secrets.size != SECRETS_BYTES) {
            secrets.zeroize()
            masterKey.zeroize()
            return KeyboxUnlockOutcome.Corrupted("keybox payload has unexpected size")
        }
        val dataKey = secrets.copyOfRange(0, DATA_KEY_BYTES)
        val guardPrivateKey = secrets.copyOfRange(DATA_KEY_BYTES, SECRETS_BYTES)
        secrets.zeroize()
        val priorAttempts = readAttempts()
        resetAttempts()
        return KeyboxUnlockOutcome.Success(
            UnlockedKeyboxSession(
                keybox = this,
                masterKey = masterKey,
                dataKey = dataKey,
                guardPrivateKey = guardPrivateKey,
                guardPublicKey = decode64(document.guardPublicKey),
                document = document,
            ),
            priorFailedAttempts = priorAttempts.failedAttempts,
            priorLastFailedAt = priorAttempts.lastFailedWallClock,
        )
    }

    fun readAttempts(): KeyboxAttempts {
        if (!attemptsFile.isFile) {
            return KeyboxAttempts()
        }
        return runCatching {
            json.decodeFromString<KeyboxAttempts>(attemptsCipher.readBytes(attemptsFile).decodeToString())
        }.getOrElse { KeyboxAttempts() }
    }

    fun delete() {
        keyboxFile.delete()
        attemptsFile.delete()
    }

    internal fun rewrap(
        masterKey: ByteArray,
        dataKey: ByteArray,
        guardPrivateKey: ByteArray,
        guardPublicKey: ByteArray,
        base: KeyboxDocument,
        newSalt: ByteArray? = null,
        checkpoint: KeyboxCheckpoint = base.checkpoint,
        credential: String = base.credential,
    ): KeyboxDocument {
        val document =
            buildDocument(
                masterKey = masterKey,
                salt = newSalt ?: decode64(base.salt),
                kdf = base.kdf,
                dataKey = dataKey,
                guardPrivateKey = guardPrivateKey,
                guardPublicKey = guardPublicKey,
                createdAtWallClock = base.createdAtWallClock,
                checkpoint = checkpoint,
                credential = credential,
            )
        writeDocument(document)
        return document
    }

    internal fun deriveMasterKey(
        password: ByteArray,
        salt: ByteArray,
        kdf: GuardKdfParams,
    ): ByteArray = crypto.argon2id(password, salt, kdf)

    internal fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    private fun buildDocument(
        masterKey: ByteArray,
        salt: ByteArray,
        kdf: GuardKdfParams,
        dataKey: ByteArray,
        guardPrivateKey: ByteArray,
        guardPublicKey: ByteArray,
        createdAtWallClock: Long,
        checkpoint: KeyboxCheckpoint,
        credential: String,
    ): KeyboxDocument {
        val nonce = randomBytes(GCM_NONCE_BYTES)
        val secrets = dataKey + guardPrivateKey
        val skeleton =
            KeyboxDocument(
                version = KEYBOX_VERSION,
                salt = encode64(salt),
                kdf = kdf,
                nonce = encode64(nonce),
                wrappedSecrets = "",
                verifier = encode64(verifierFor(masterKey)),
                guardPublicKey = encode64(guardPublicKey),
                createdAtWallClock = createdAtWallClock,
                checkpoint = checkpoint,
                credential = credential,
            )
        val wrapped =
            gcmEncrypt(
                key = masterKey,
                nonce = nonce,
                plaintext = secrets,
                aad = canonicalAad(skeleton),
            )
        secrets.zeroize()
        return skeleton.copy(wrappedSecrets = encode64(wrapped))
    }

    private fun readDocument(): KeyboxDocument {
        val raw = keyboxFile.readBytes()
        if (raw.isNotEmpty() && raw[0] == LEGACY_JSON_FIRST_BYTE) {
            // Legacy v1 file: plain JSON on disk. Parse it and migrate to the
            // hardware-wrapped format best-effort — if the Keystore write fails the
            // plain file stays readable and we retry on the next read; the password
            // path must never break because migration could not complete.
            val document = json.decodeFromString<KeyboxDocument>(raw.decodeToString())
            runCatching { writeDocument(document) }
            return document
        }
        return json.decodeFromString<KeyboxDocument>(keyboxCipher.readBytes(keyboxFile).decodeToString())
    }

    private fun writeDocument(document: KeyboxDocument) {
        keyboxCipher.writeBytesAtomic(
            keyboxFile,
            json.encodeToString(KeyboxDocument.serializer(), document).encodeToByteArray(),
        )
    }

    internal fun recordFailedAttempt(): KeyboxAttempts {
        val current = readAttempts()
        val next =
            current.copy(
                failedAttempts = current.failedAttempts + 1,
                lastFailedWallClock = wallClock(),
            )
        runCatching {
            attemptsCipher.writeBytesAtomic(
                attemptsFile,
                json.encodeToString(KeyboxAttempts.serializer(), next).encodeToByteArray()
            )
        }
        return next
    }

    internal fun resetAttempts() {
        attemptsFile.delete()
    }

    /**
     * Backoff delay still owed, computed from the PERSISTED attempt state rather than in-process
     * counters, so the exponential throttle survives a kill/restart instead of resetting to zero
     * (the unlock UI seeds its countdown from this on cold start). A backwards wall clock — a naive
     * rollback-the-clock bypass — yields the full window instead of 0. Returns 0 when nothing is
     * owed. Root can still delete attempts.bin; this hardens the non-root/UI path per task_new.md.
     */
    fun remainingBackoffMs(): Long {
        val attempts = readAttempts()
        if (attempts.failedAttempts <= 0) {
            return 0L
        }
        val window = backoffDelayMs(attempts.failedAttempts)
        if (window <= 0L) {
            return 0L
        }
        val sinceLastFailure = wallClock() - attempts.lastFailedWallClock
        return when {
            sinceLastFailure < 0L -> window
            sinceLastFailure >= window -> 0L
            else -> window - sinceLastFailure
        }
    }

    /**
     * Canonical AAD string binding every password-independent field to the wrapped
     * secrets. Built by hand (not a serializer) so its bytes never depend on library
     * behavior; `wrappedSecrets` itself is excluded (it is what the AAD protects).
     */
    private fun canonicalAad(document: KeyboxDocument): ByteArray =
        buildString {
            append("fhkb").append(document.version)
            append("|salt=").append(document.salt)
            append("|kdf=").append(document.kdf.memKib)
            append(',').append(document.kdf.ops)
            append(',').append(document.kdf.parallelism)
            append("|pub=").append(document.guardPublicKey)
            append("|created=").append(document.createdAtWallClock)
            append("|cp=").append(document.checkpoint.seq)
            append(',').append(document.checkpoint.headHash)
            // v1 documents were sealed without the credential field; appending it for
            // them would break GCM on every pre-existing box.
            if (document.version >= CREDENTIAL_AAD_MIN_VERSION) {
                append("|cred=").append(document.credential)
            }
        }.encodeToByteArray()

    private fun verifierFor(masterKey: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(masterKey, HMAC_ALGORITHM))
        return mac.doFinal(VERIFIER_LABEL.encodeToByteArray())
    }

    private fun gcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray = gcm(Cipher.ENCRYPT_MODE, key, nonce, plaintext, aad)

    private fun gcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = gcm(Cipher.DECRYPT_MODE, key, nonce, ciphertext, aad)

    private fun gcm(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    private fun encode64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode64(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        const val KEYBOX_VERSION = 2
        const val CREDENTIAL_AAD_MIN_VERSION = 2

        // Legacy v1 keyboxes are bare JSON; the Keystore envelope starts with a
        // version byte (0x01), so '{' unambiguously identifies the old format.
        private const val LEGACY_JSON_FIRST_BYTE = '{'.code.toByte()
        const val DATA_KEY_BYTES = 32
        const val SECRETS_BYTES = 64
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val VERIFIER_LABEL = "foxhole-verify"
        const val FIRST_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        private const val MAX_BACKOFF_SHIFT = 8

        fun backoffDelayMs(failedAttempts: Int): Long {
            if (failedAttempts <= 0) {
                return 0L
            }
            val shift = min(failedAttempts - 1, MAX_BACKOFF_SHIFT)
            return min(FIRST_BACKOFF_MS shl shift, MAX_BACKOFF_MS)
        }
    }
}

/**
 * Live unlocked state: retains the master key so checkpoint updates and password
 * changes can re-authenticate the box without re-running Argon2. Zeroed by destroy().
 */
internal class UnlockedKeyboxSession internal constructor(
    private val keybox: SecureKeybox,
    private val masterKey: ByteArray,
    val dataKey: ByteArray,
    val guardPrivateKey: ByteArray,
    val guardPublicKey: ByteArray,
    private var document: KeyboxDocument,
) {
    val checkpoint: KeyboxCheckpoint
        get() = document.checkpoint

    /** Private copy for the biometric enrolment path; the caller must zero it. */
    internal fun copyMasterKey(): ByteArray = masterKey.copyOf()

    fun updateJournalCheckpoint(
        seq: Long,
        headHash: String,
    ) {
        document =
            keybox.rewrap(
                masterKey = masterKey,
                dataKey = dataKey,
                guardPrivateKey = guardPrivateKey,
                guardPublicKey = guardPublicKey,
                base = document,
                checkpoint = KeyboxCheckpoint(seq = seq, headHash = headHash),
            )
    }

    fun changePassword(
        newPassword: ByteArray,
        credential: String = KeyboxDocument.CREDENTIAL_PIN,
    ) {
        val newSalt = keybox.randomBytes(GuardCrypto.SALT_BYTES)
        val newMasterKey = keybox.deriveMasterKey(newPassword, newSalt, document.kdf)
        document =
            keybox.rewrap(
                masterKey = newMasterKey,
                dataKey = dataKey,
                guardPrivateKey = guardPrivateKey,
                guardPublicKey = guardPublicKey,
                base = document,
                newSalt = newSalt,
                credential = credential,
            )
        newMasterKey.copyInto(masterKey)
        newMasterKey.zeroize()
    }

    fun destroy() {
        masterKey.zeroize()
        dataKey.zeroize()
        guardPrivateKey.zeroize()
    }
}
