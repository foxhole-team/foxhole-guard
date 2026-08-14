package com.foxhole.guard.core.security

import kotlinx.serialization.Serializable

/**
 * Journal checkpoint anchored inside the keybox: the last verified head of the guard
 * journal hash chain. Updating it requires the master key (it rides the GCM AAD), so
 * a wholesale journal rewrite after the last unlock becomes detectable.
 */
@Serializable
internal data class KeyboxCheckpoint(
    val seq: Long = NO_CHECKPOINT_SEQ,
    val headHash: String = "",
) {
    companion object {
        const val NO_CHECKPOINT_SEQ = -1L
    }
}

/**
 * On-disk keybox document (`filesDir/secure/keybox.bin`). Version 1 was plain JSON
 * (openable by password alone). Since version 2 the JSON is additionally wrapped in a
 * Keystore-backed file cipher: a 6-digit PIN has only 10^6 candidates, so the file
 * must be useless off-device — brute force now requires the hardware key. The app
 * already depends on the same Keystore surviving (settings blob, attempts file), so
 * this adds no new loss mode. All password-independent fields are bound to
 * `wrappedSecrets` through a canonical AAD string, so editing any of them breaks GCM
 * on the next unlock.
 */
@Serializable
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
    // What kind of secret opens the box: v1 documents carry no field and default to
    // the legacy free-form password; PIN-created/changed boxes say so, and the unlock
    // screen picks the keypad vs. the text field from this (readable while locked).
    val credential: String = KeyboxDocument.CREDENTIAL_PASSWORD,
) {
    companion object {
        const val CREDENTIAL_PASSWORD = "password"
        const val CREDENTIAL_PIN = "pin"
    }
}

/**
 * Failed-attempt state. Kept OUTSIDE the password domain (a failed attempt has no
 * master key to re-authenticate the box with), protected by a Keystore-backed cipher
 * instead: honest about root — who can reset this file but can also delete everything.
 * In-process backoff state does not depend on it within a single app session.
 */
@Serializable
internal data class KeyboxAttempts(
    val failedAttempts: Int = 0,
    val lastFailedWallClock: Long = 0,
)
