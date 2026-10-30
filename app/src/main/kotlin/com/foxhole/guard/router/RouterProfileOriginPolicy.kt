package com.foxhole.guard.router

import java.security.MessageDigest

internal fun routerProfileDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun validRouterProfileMarker(schema: Int, kind: String, pairingId: String, clientId: String,
    uriSha256: String, uri: String, expectedPairingId: String): Boolean = schema == 1 && kind == "foxhole-openwrt-client" &&
    pairingId.matches(Regex("pair-[a-f0-9]{32}")) && pairingId == expectedPairingId &&
    clientId.matches(Regex("client-[a-f0-9]{12}")) && uriSha256.matches(Regex("[a-f0-9]{64}")) &&
    uri.startsWith("hysteria2://") && uri.toByteArray(Charsets.UTF_8).size in 1..16384 && uri.none { it.code in 0..0x1f || it.code in 0x7f..0x9f } &&
    routerProfileDigest(uri) == uriSha256

// A parsed marker is only a hint. Authority comes from an authenticated local record bound to this exact profile.
internal fun routerLocalProfileMatches(profileId: String, configSha256: String, storedProfileId: String,
    storedConfigSha256: String): Boolean = profileId == storedProfileId && configSha256 == storedConfigSha256

internal data class RouterConnectedProfile(val id: String, val configSha256: String,
    val connectionGeneration: String, val networkHandle: Long) {
    override fun toString(): String = "RouterConnectedProfile"
}

internal data class RouterProfileProbeKey(val profileId: String, val configSha256: String,
    val pairingId: String, val connectionGeneration: String) {
    override fun toString(): String = "RouterProfileProbeKey"
}

internal class RouterProfileProbeGate {
    private var lastAttempt: RouterProfileProbeKey? = null
    fun claim(key: RouterProfileProbeKey): Boolean {
        if (lastAttempt == key) return false
        lastAttempt = key
        return true
    }
}
