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
    val homeVpnDefaultPending: Boolean = false,
    val hideOfflineDevices: Boolean = true,
    val homeWifiHashes: Set<String> = emptySet(),
) {
    fun afterSuccessfulPairing(): RouterControlPreferences = copy(enabled = true, homeVpnDefaultPending = false)

    // An observed public address does not prove a static address or replace the user's confirmation.
    @Suppress("UNUSED_PARAMETER")
    fun withPublicIpv4Evidence(available: Boolean?): RouterControlPreferences =
        if (homeVpnDefaultPending) copy(homeVpnDefaultPending = false) else this

    fun validate() { require(pairingId.matches(Regex("pair-[a-f0-9]{32}")) &&
        (countryCode.isEmpty() || countryCode in Locale.getISOCountries().toSet()) &&
        (!homeVpnManagementEnabled || publicIpConfirmed) && homeWifiHashes.size <= 8 &&
        homeWifiHashes.all { it.matches(Regex("[a-f0-9]{64}")) }) }
}

internal interface RouterControlPreferenceStore {
    suspend fun read(pairingId: String): RouterControlPreferences
    suspend fun save(preferences: RouterControlPreferences)
    suspend fun readAuthenticatedVersion(pairingId: String, certificateSha256: String): String? = null
    suspend fun saveAuthenticatedVersion(pairingId: String, certificateSha256: String, version: String): Boolean = false
}

internal class LocalRouterControlPreferenceStore(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pairings: EncryptedRouterPairingStore = EncryptedRouterPairingStore(context, io)) : RouterControlPreferenceStore {
    private val file = context.getSharedPreferences("vpn-router-controls", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    private val versions by lazy { appContext.getSharedPreferences("router-paired-version-cache", Context.MODE_PRIVATE) }
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
    override suspend fun readAuthenticatedVersion(pairingId: String, certificateSha256: String): String? = withContext(io) {
        routerDiscoveryPackageVersion(versions.getString(versionKey(pairingId, certificateSha256), null))
    }
    override suspend fun saveAuthenticatedVersion(pairingId: String, certificateSha256: String, version: String): Boolean = withContext(io) {
        require(routerDiscoveryPackageVersion(version) == version)
        versions.edit().putString(versionKey(pairingId, certificateSha256), version).commit()
    }
    private fun versionKey(pairingId: String, certificateSha256: String): String {
        require(pairingId.matches(Regex("pair-[a-f0-9]{32}")) && certificateSha256.matches(Regex("[a-f0-9]{64}")))
        return "$pairingId-$certificateSha256"
    }
}
