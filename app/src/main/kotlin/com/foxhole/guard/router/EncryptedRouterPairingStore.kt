package com.foxhole.guard.router

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class EncryptedRouterPairingStore(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO,
    storageId: String = "router-pairing.v1") : RouterPairingStore {
    init { require(storageId == "router-pairing.v1" || storageId.matches(Regex("router-pairing-qa-[a-f0-9]{32}"))) }
    private val lock = locks.computeIfAbsent(File(context.noBackupFilesDir, "vpn-$storageId").absolutePath) { Mutex() }
    private val file = AtomicFile(File(context.noBackupFilesDir, "vpn-$storageId"))
    private val changesMutable = revisions.computeIfAbsent(file.baseFile.absolutePath) { MutableStateFlow(0L) }
    val changes: StateFlow<Long> = changesMutable
    private val alias = "${context.packageName}.$storageId"
    private val aad = "$alias:1".toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    override suspend fun read(): RouterPairing? = readVault { value -> value.routers.singleOrNull { it.pairing.id == value.selectedId }?.pairing?.copy() }
    override suspend fun readAll(): List<RouterPairing> = readVault { value -> value.routers.map { it.pairing.copy() } }
    override suspend fun readById(id: String): RouterPairing? = readVault { value -> value.routers.singleOrNull { it.pairing.id == id }?.pairing?.copy() }
    override suspend fun listSaved(): RouterSavedInventory = readVault(RouterSavedVault::inventory)
    override suspend fun selectSaved(id: String) = changeVault { it.selected(id) }
    override suspend fun save(pairing: RouterPairing) = changeVault { it.withPairing(pairing) }
    override suspend fun remove(expectedId: String) = changeVault { it.without(expectedId) }
    internal suspend fun readPreferences(id: String): RouterControlPreferences? = readVault { value -> value.routers.singleOrNull { it.pairing.id == id }?.preferences }
    internal suspend fun savePreferences(preferences: RouterControlPreferences) = changeVault { it.withPreferences(preferences) }

    internal suspend fun trustedProfile(profileId: String, configSha256: String): RouterTrustedProfile? = readVault { vault ->
        vault.routers.singleOrNull { it.pairing.id == vault.selectedId }?.let { entry ->
            entry.trustedProfiles.singleOrNull { routerLocalProfileMatches(profileId, configSha256, it.profileId, it.configSha256) &&
                it.certificateSha256 == entry.pairing.identity.certificateSha256 && it.marker.pairingId == entry.pairing.id }
        }
    }
    internal suspend fun trustProfile(identity: RouterTlsIdentity, profile: RouterTrustedProfile) = changeVault {
        it.withTrustedProfile(identity, profile)
    }

    private suspend fun <T> readVault(block: (RouterSavedVault) -> T): T = withContext(io) { lock.withLock {
        val value = readLocked()
        try { block(value) } finally { value.cleared() }
    } }

    private suspend fun changeVault(block: (RouterSavedVault) -> RouterSavedVault) = withContext(io) { lock.withLock {
        val previous = readLocked()
        var updated: RouterSavedVault? = null
        try { val next = block(previous); updated = next; writeLocked(next); changesMutable.update { it + 1 } }
        finally { updated?.cleared(); previous.cleared() }
    } }

    private fun writeLocked(value: RouterSavedVault) {
        if (value.routers.isEmpty()) { file.delete(); return }
        val plaintext = RouterSavedVaultCodec.encode(value).toByteArray(Charsets.UTF_8)
        require(plaintext.size <= MAX_BYTES)
        val encrypted = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(aad)
            val result = cipher.doFinal(plaintext)
            require(cipher.iv.size == 12)
            ByteBuffer.allocate(5 + 12 + result.size).putInt(MAGIC).put(12.toByte()).put(cipher.iv).put(result).array()
        } finally { plaintext.fill(0) }
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private fun readLocked(): RouterSavedVault {
        val recovery = File(file.baseFile.path + ".bak")
        if (!file.baseFile.exists() && !recovery.exists()) return RouterSavedVault()
        val source = if (recovery.exists()) recovery else file.baseFile
        require(source.length() in 34..(MAX_BYTES + 64).toLong())
        val buffer = ByteBuffer.wrap(file.readFully())
        require(buffer.int == MAGIC && buffer.get().toInt() == 12 && buffer.remaining() > 28)
        val nonce = ByteArray(12).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(ciphertext)
        return try {
            requireBoundedRouterJson(plaintext, maxDepth = 12, maxNodes = 1536)
            val text = plaintext.toString(Charsets.UTF_8)
            val value = RouterSavedVaultCodec.decode(text)
            if (!json.parseToJsonElement(text).jsonObject.containsKey("schema")) {
                try { writeLocked(value) } catch (failure: Exception) { value.cleared(); throw failure }
            }
            value
        } finally { plaintext.fill(0) }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create && !file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) { "router_key_unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }

    private companion object {
        const val MAGIC = 0x46525031
        const val MAX_BYTES = 64 * 1024
        val locks = ConcurrentHashMap<String, Mutex>()
        val revisions = ConcurrentHashMap<String, MutableStateFlow<Long>>()
    }
}
