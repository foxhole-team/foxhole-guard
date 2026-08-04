package com.foxhole.guard.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Process-scoped home for the unlocked key material. When the app UI re-locks we
 * zero the Java-side copies here and flip [dataKeyAvailable]; the live SQLCipher
 * connection stays open (its key is already in native memory - decision #8), so the
 * VPN keeps running while the UI is locked.
 */
class SecureSessionHolder {
    private val availableState = MutableStateFlow(false)
    val dataKeyAvailable: StateFlow<Boolean> = availableState.asStateFlow()

    @Volatile
    private var dataKey: ByteArray? = null

    @Volatile
    private var guardPrivateKey: ByteArray? = null

    @Volatile
    private var guardPublicKey: ByteArray? = null

    val isUnlocked: Boolean
        get() = dataKey != null

    @Synchronized
    fun install(
        dataKey: ByteArray,
        guardPrivateKey: ByteArray,
        guardPublicKey: ByteArray,
    ) {
        clearJavaCopies()
        this.dataKey = dataKey.copyOf()
        this.guardPrivateKey = guardPrivateKey.copyOf()
        this.guardPublicKey = guardPublicKey.copyOf()
        availableState.value = true
    }

    /** Returns a private copy of the dataKey; caller must zero it after use. */
    @Synchronized
    fun copyDataKeyOrNull(): ByteArray? = dataKey?.copyOf()

    @Synchronized
    fun copyGuardPrivateKeyOrNull(): ByteArray? = guardPrivateKey?.copyOf()

    @Synchronized
    fun guardPublicKeyOrNull(): ByteArray? = guardPublicKey?.copyOf()

    @Synchronized
    fun clearJavaCopies() {
        dataKey?.zeroize()
        guardPrivateKey?.zeroize()
        guardPublicKey?.zeroize()
        dataKey = null
        guardPrivateKey = null
        guardPublicKey = null
        availableState.value = false
    }

    suspend fun awaitDataKey(): ByteArray {
        // Loop rather than recurse: a clear() racing between the flow emit and the copy makes the
        // copy null, and a tail-recursive retry would grow the stack under a persistent race.
        while (true) {
            dataKeyAvailable.first { it }
            copyDataKeyOrNull()?.let { key -> return key }
        }
    }
}
