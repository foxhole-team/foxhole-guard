package com.foxhole.guard.router

import kotlinx.serialization.Serializable
import java.security.SecureRandom

@Serializable
internal class RouterPairing(
    val identity: RouterTlsIdentity,
    val id: String,
    val credential: ByteArray,
    val name: String,
    val active: Boolean = false,
    val pendingServerId: String? = null,
    val pendingSyncSha256: String? = null,
) {
    fun validate() {
        require(id.matches(Regex("pair-[a-f0-9]{32}")) && credential.size == 32)
        require(name.isNotBlank() && name.length <= 128 && name.none(Char::isISOControl))
        require((pendingServerId == null && pendingSyncSha256 == null) ||
            pendingServerId?.matches(Regex("server-[a-f0-9]{12}")) == true && pendingSyncSha256?.matches(Regex("[a-f0-9]{64}")) == true)
    }
    fun activated(): RouterPairing = RouterPairing(identity, id, credential.copyOf(), name, true, pendingServerId, pendingSyncSha256)
    fun copy(): RouterPairing = RouterPairing(identity, id, credential.copyOf(), name, active, pendingServerId, pendingSyncSha256)
    fun withPendingSync(serverId: String?, syncSha256: String?): RouterPairing =
        RouterPairing(identity, id, credential.copyOf(), name, active, serverId, syncSha256).also(RouterPairing::validate)
    fun atVerifiedIdentity(observed: RouterTlsIdentity): RouterPairing {
        require(identity.certificateSha256 == observed.certificateSha256)
        return RouterPairing(observed, id, credential.copyOf(), name, active, pendingServerId, pendingSyncSha256)
    }
    fun clear() { credential.fill(0) }
    override fun toString(): String = "RouterPairing(id=$id, active=$active)"

    companion object {
        fun create(identity: RouterTlsIdentity, name: String, random: SecureRandom = SecureRandom()): RouterPairing {
            val identifier = ByteArray(16).also(random::nextBytes)
            val credential = ByteArray(32).also(random::nextBytes)
            return RouterPairing(identity, "pair-" + identifier.joinToString("") { "%02x".format(it.toInt() and 255) }, credential, name)
                .also(RouterPairing::validate)
        }
    }
}

internal interface RouterPairingStore {
    suspend fun read(): RouterPairing?
    suspend fun save(pairing: RouterPairing)
    suspend fun remove(expectedId: String)
    suspend fun listSaved(): RouterSavedInventory {
        val saved = read() ?: return RouterSavedInventory(emptyList(), null)
        return try { RouterSavedInventory(listOf(RouterSavedSummary(saved.id, 1, null, saved.identity.endpoint.address)), saved.id) }
        finally { saved.clear() }
    }
    suspend fun readAll(): List<RouterPairing> = listOfNotNull(read())
    suspend fun readById(id: String): RouterPairing? {
        val saved = read() ?: return null
        if (saved.id == id) return saved
        saved.clear(); return null
    }
    suspend fun selectSaved(id: String) {
        val saved = read() ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        try { if (saved.id != id) throw RouterApiFailure(RouterApiError.CONFLICT) } finally { saved.clear() }
    }
}
