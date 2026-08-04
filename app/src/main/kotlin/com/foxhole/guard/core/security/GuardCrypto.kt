package com.foxhole.guard.core.security

import kotlinx.serialization.Serializable

/**
 * Argon2id parameters pinned inside a keybox at creation time, so later changes to
 * the calibration defaults never break unlocking of an existing box.
 * libsodium's crypto_pwhash implements Argon2id with a fixed internal parallelism of 1.
 */
@Serializable
data class GuardKdfParams(
    val memKib: Int,
    val ops: Int,
    // Inert: libsodium's crypto_pwhash fixes lanes at 1 and exposes no parallelism argument, so this
    // is never fed to the KDF. Kept (defaulted to 1) only so persisted keyboxes stay wire-compatible;
    // do not treat it as a tunable — changing it does not change the derivation.
    val parallelism: Int = 1,
)

class GuardKeypair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)

/**
 * The libsodium surface used by the security core, isolated behind an interface so
 * keybox/journal logic stays unit-testable on the JVM where the Android natives
 * cannot load.
 */
interface GuardCrypto {
    fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        params: GuardKdfParams,
        outputLength: Int = MASTER_KEY_BYTES,
    ): ByteArray

    fun sealedBoxKeypair(): GuardKeypair

    fun seal(
        payload: ByteArray,
        publicKey: ByteArray,
    ): ByteArray

    fun openSealed(
        sealed: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray

    companion object {
        const val MASTER_KEY_BYTES = 32
        const val SALT_BYTES = 16
        const val X25519_KEY_BYTES = 32
        const val SEAL_OVERHEAD_BYTES = 48
    }
}
