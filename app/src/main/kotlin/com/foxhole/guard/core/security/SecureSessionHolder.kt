package com.foxhole.guard.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

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
        while (true) {
            dataKeyAvailable.first { it }
            copyDataKeyOrNull()?.let { key -> return key }
        }
    }
}
