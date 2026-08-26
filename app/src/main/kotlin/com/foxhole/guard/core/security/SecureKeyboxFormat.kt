package com.foxhole.guard.core.security

import kotlinx.serialization.Serializable

@Serializable
internal data class KeyboxCheckpoint(
    val seq: Long = NO_CHECKPOINT_SEQ,
    val headHash: String = "",
) {
    companion object {
        const val NO_CHECKPOINT_SEQ = -1L
    }
}

@Serializable
// V2 hardware-wraps the password-derived box; canonical AAD binds every clear field to wrappedSecrets.
internal data class KeyboxDocument(
    val version: Int,
    val salt: String,
    val kdf: GuardKdfParams,
    val nonce: String,
    val wrappedSecrets: String,
    val verifier: String,
    val guardPublicKey: String,
    val createdAtWallClock: Long,
    val checkpoint: KeyboxCheckpoint = KeyboxCheckpoint(),

    val credential: String = KeyboxDocument.CREDENTIAL_PASSWORD,
) {
    companion object {
        const val CREDENTIAL_PASSWORD = "password"
        const val CREDENTIAL_PIN = "pin"
    }
}

@Serializable
internal data class KeyboxAttempts(
    val failedAttempts: Int = 0,
    val lastFailedWallClock: Long = 0,
)
