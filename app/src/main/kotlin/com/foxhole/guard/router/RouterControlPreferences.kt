package com.foxhole.guard.router

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
internal data class RouterControlPreferences(
    val pairingId: String,
    val enabled: Boolean = false,
    val countryCode: String = "",
    val countrySitesDirect: Boolean = true,
    val homeAutodetection: Boolean = false,
    val publicIpConfirmed: Boolean = false,
    val countryAuto: Boolean = true,
    val homeVpnManagementEnabled: Boolean = false,
) {
    fun validate() { require(pairingId.matches(Regex("pair-[a-f0-9]{32}")) &&
        (countryCode.isEmpty() || countryCode in Locale.getISOCountries().toSet()) &&
        (!homeVpnManagementEnabled || publicIpConfirmed)) }
}

internal interface RouterControlPreferenceStore {
    suspend fun read(pairingId: String): RouterControlPreferences
    suspend fun save(preferences: RouterControlPreferences)
}

internal class LocalRouterControlPreferenceStore(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pairings: EncryptedRouterPairingStore = EncryptedRouterPairingStore(context, io)) : RouterControlPreferenceStore {
    private val file = context.getSharedPreferences("vpn-router-controls", Context.MODE_PRIVATE)
    override suspend fun read(pairingId: String): RouterControlPreferences = withContext(io) {
        pairings.readPreferences(pairingId)?.let { return@withContext it }
        if (file.getString("pairing", null) != pairingId) return@withContext RouterControlPreferences(pairingId)
        val migrated = RouterControlPreferences(pairingId, file.getBoolean("enabled", false),
            file.getString("country", "").orEmpty().takeIf { it in Locale.getISOCountries().toSet() }.orEmpty(),
            file.getBoolean("country-direct", true), file.getBoolean("auto-home", false),
            file.getBoolean("public-ip-confirmed", false), file.getBoolean("country-auto", true))
        pairings.savePreferences(migrated)
        if (!file.edit().clear().commit()) throw IOException("router_preferences_failed")
        migrated
    }
    override suspend fun save(preferences: RouterControlPreferences) = withContext(io) {
        pairings.savePreferences(preferences)
    }
}
