package com.foxhole.core.runtime

/**
 * Read-only runtime-facing slice of the profile store.
 *
 * Validation and dashboard refresh need the sanitized base config shape, but must never build a
 * live session: session construction prepares Tor and may start i2pd. Keeping this boundary pure
 * prevents a foreground reconcile or an IP refresh from acquiring child-process ownership.
 */
interface RuntimeProfiles {
    suspend fun getResolvedConfig(profileId: Long): String
}
