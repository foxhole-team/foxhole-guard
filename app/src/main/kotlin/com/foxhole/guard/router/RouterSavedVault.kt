package com.foxhole.guard.router

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal const val MAX_SAVED_ROUTERS = 3
internal enum class RouterSavedStatus { UNKNOWN, AVAILABLE, UNAVAILABLE }
internal data class RouterSavedSummary(val id: String, val number: Int, val label: String?, val address: String,
    val status: RouterSavedStatus = RouterSavedStatus.UNKNOWN)
internal data class RouterSavedInventory(val routers: List<RouterSavedSummary>, val selectedId: String?)

@Serializable
internal data class RouterSavedEntry(val pairing: RouterPairing, val number: Int, val label: String? = null,
    val preferences: RouterControlPreferences? = null, val trustedProfiles: List<RouterTrustedProfile> = emptyList())

@Serializable
internal data class RouterSavedVault(val schema: Int = 2, val selectedId: String? = null,
    val routers: List<RouterSavedEntry> = emptyList()) {
    fun validate() {
        require(schema == 2 && routers.size <= MAX_SAVED_ROUTERS)
        require((routers.isEmpty() && selectedId == null) || routers.any { it.pairing.id == selectedId })
        require(routers.map { it.pairing.id }.distinct().size == routers.size)
        require(routers.map { it.number }.distinct().size == routers.size)
        require(routers.map { it.pairing.identity.certificateSha256 }.distinct().size == routers.size)
        routers.forEach { entry ->
            entry.pairing.validate()
            require(entry.number in 1..MAX_SAVED_ROUTERS)
            require(entry.label == null || entry.label.isNotBlank() && entry.label.length <= 64 && entry.label.none(Char::isISOControl))
            entry.preferences?.let { require(it.pairingId == entry.pairing.id); it.validate() }
            require(entry.trustedProfiles.size <= 32 && entry.trustedProfiles.map { it.profileId }.distinct().size == entry.trustedProfiles.size)
            entry.trustedProfiles.forEach { it.validate(entry.pairing) }
        }
    }
    fun inventory() = RouterSavedInventory(routers.sortedBy { it.number }.map { RouterSavedSummary(it.pairing.id, it.number, it.label,
        it.pairing.identity.endpoint.address) }, selectedId)
    fun cleared() { routers.forEach { it.pairing.clear() } }
    fun withPairing(pairing: RouterPairing): RouterSavedVault {
        pairing.validate()
        val existing = routers.singleOrNull { it.pairing.id == pairing.id }
        if (existing == null && routers.size == MAX_SAVED_ROUTERS) throw RouterApiFailure(RouterApiError.LIMIT)
        if (existing != null && existing.pairing.identity.certificateSha256 != pairing.identity.certificateSha256) throw RouterIdentityChanged()
        if (routers.any { it.pairing.id != pairing.id && it.pairing.identity.certificateSha256 == pairing.identity.certificateSha256 })
            throw RouterApiFailure(RouterApiError.CONFLICT)
        val number = existing?.number ?: (1..MAX_SAVED_ROUTERS).first { index -> routers.none { it.number == index } }
        val replacement = RouterSavedEntry(pairing.copy(), number, existing?.label, existing?.preferences, existing?.trustedProfiles.orEmpty())
        return copy(selectedId = if (existing == null) pairing.id else selectedId,
            routers = (routers.filterNot { it.pairing.id == pairing.id }.map { it.copy(pairing = it.pairing.copy()) } + replacement).sortedBy { it.number }).also(RouterSavedVault::validate)
    }
    fun selected(id: String): RouterSavedVault {
        if (routers.none { it.pairing.id == id }) throw RouterApiFailure(RouterApiError.CONFLICT)
        return copy(selectedId = id, routers = routers.map { it.copy(pairing = it.pairing.copy()) })
    }
    fun without(id: String): RouterSavedVault {
        val remaining = routers.filterNot { it.pairing.id == id }.map { it.copy(pairing = it.pairing.copy()) }
        return copy(selectedId = selectedId.takeIf { selected -> remaining.any { it.pairing.id == selected } } ?: remaining.firstOrNull()?.pairing?.id,
            routers = remaining).also(RouterSavedVault::validate)
    }
    fun withTrustedProfile(expectedIdentity: RouterTlsIdentity, profile: RouterTrustedProfile): RouterSavedVault {
        val entry = routers.singleOrNull { it.pairing.id == selectedId && it.pairing.id == profile.marker.pairingId }
            ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        if (entry.pairing.identity != expectedIdentity || !entry.pairing.active) throw RouterIdentityChanged()
        profile.validate(entry.pairing)
        return copy(routers = routers.map { row -> row.copy(pairing = row.pairing.copy(), trustedProfiles =
            if (row.pairing.id == entry.pairing.id) (row.trustedProfiles.filterNot { it.profileId == profile.profileId } + profile).takeLast(32)
            else row.trustedProfiles) }).also(RouterSavedVault::validate)
    }

    fun withPreferences(preferences: RouterControlPreferences): RouterSavedVault {
        preferences.validate()
        if (routers.none { it.pairing.id == preferences.pairingId }) throw RouterApiFailure(RouterApiError.CONFLICT)
        return copy(routers = routers.map { entry -> entry.copy(pairing = entry.pairing.copy(),
            preferences = if (entry.pairing.id == preferences.pairingId) preferences else entry.preferences) })
    }
}

internal object RouterSavedVaultCodec {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    fun decode(plaintext: String): RouterSavedVault {
        val root = json.parseToJsonElement(plaintext).jsonObject
        val value = if (root.containsKey("schema")) json.decodeFromString<RouterSavedVault>(plaintext)
            else RouterSavedVault(routers = listOf(RouterSavedEntry(json.decodeFromString<RouterPairing>(plaintext), 1))).let {
                it.copy(selectedId = it.routers.single().pairing.id)
            }
        try { value.validate(); return value }
        catch (failure: Exception) { value.cleared(); throw failure }
    }
    fun encode(value: RouterSavedVault): String { value.validate(); return json.encodeToString(value) }
}
