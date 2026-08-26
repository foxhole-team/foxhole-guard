package com.foxhole.guard.core.security

import kotlinx.serialization.Serializable

@Serializable
data class GuardKdfParams(
    val memKib: Int,
    val ops: Int,

    val parallelism: Int = 1,
)

class GuardKeypair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)

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
