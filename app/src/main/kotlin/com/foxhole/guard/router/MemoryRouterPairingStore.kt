package com.foxhole.guard.router

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal open class MemoryRouterPairingStore : RouterPairingStore, RouterControlPreferenceStore, AutoCloseable {
    private val mutex = Mutex()
    @Volatile private var value = RouterSavedVault()
    val size: Int get() = value.routers.size
    override suspend fun read(): RouterPairing? = mutex.withLock { value.routers.singleOrNull { it.pairing.id == value.selectedId }?.pairing?.copy() }
    override suspend fun readById(id: String): RouterPairing? = mutex.withLock { value.routers.singleOrNull { it.pairing.id == id }?.pairing?.copy() }
    override suspend fun readAll(): List<RouterPairing> = mutex.withLock { value.routers.map { it.pairing.copy() } }
    override suspend fun listSaved(): RouterSavedInventory = mutex.withLock { value.inventory() }
    override suspend fun save(pairing: RouterPairing) = change { it.withPairing(pairing) }
    override suspend fun selectSaved(id: String) = change { it.selected(id) }
    override suspend fun remove(expectedId: String) = change { it.without(expectedId) }
    override suspend fun read(pairingId: String): RouterControlPreferences = mutex.withLock {
        value.routers.singleOrNull { it.pairing.id == pairingId }?.preferences ?: RouterControlPreferences(pairingId)
    }
    override suspend fun save(preferences: RouterControlPreferences) = change { it.withPreferences(preferences) }
    private suspend fun change(block: (RouterSavedVault) -> RouterSavedVault) = mutex.withLock {
        val next = block(value)
        value.cleared(); value = next
    }
    override fun close() { value.cleared(); value = RouterSavedVault() }
}
