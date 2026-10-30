package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import com.foxhole.core.network.requirePublicRemoteHost

internal class RouterProviderResolution(val addresses: List<InetAddress>, val expiresAtElapsedMs: Long) {
    override fun toString(): String = "RouterProviderResolution"
}

internal fun routerProviderHostname(value: String): String {
    val host = value.lowercase(Locale.ROOT)
    host.requirePublicRemoteHost(resolveHost = false)
    require(host.length in 3..253 && host.contains('.') && host.matches(Regex("[a-z0-9.-]+")) &&
        host.split('.').all { it.matches(Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")) } &&
        host.substringAfterLast('.') !in setOf("local", "localhost", "lan", "onion", "invalid") && routerPublicIpLiteral(host) == null)
    return host
}

internal fun parseRouterProviderResolution(value: JsonObject, expectedPair: String, expectedHost: String,
    currentRouterUptimeMs: Long, receivedAt: Long, requestDurationMs: Long): RouterProviderResolution {
    fun invalid(): Nothing = throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    fun text(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: invalid()
    fun number(key: String) = (value[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: invalid()
    if (value.keys != setOf("schema", "pairingId", "host", "addresses", "ttlMs", "checkedUptimeMs") ||
        number("schema") != 1L || text("pairingId") != expectedPair || text("host") != expectedHost ||
        !expectedPair.matches(Regex("pair-[a-f0-9]{32}")) || routerProviderHostname(expectedHost) != expectedHost ||
        currentRouterUptimeMs !in 0..9007199254740991L || receivedAt < 0 || requestDurationMs !in 0..5000) invalid()
    val ttl = number("ttlMs")
    val checked = number("checkedUptimeMs")
    val age = currentRouterUptimeMs - checked
    if (checked !in 0..9007199254740991L || ttl !in 0..60000 || age !in -1000..60000 ||
        ttl > 60000 - age.coerceAtLeast(0)) invalid()
    val literals = (value["addresses"] as? JsonArray)?.map {
        (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull ?: invalid()
    } ?: invalid()
    if (literals.size !in 1..8 || literals.distinct().size != literals.size) invalid()
    val addresses = literals.map { address ->
        routerPublicIpLiteral(address)?.takeIf { it is Inet4Address && it.hostAddress == address } ?: invalid()
    }
    val remaining = (ttl - requestDurationMs).coerceAtLeast(0)
    if (receivedAt > Long.MAX_VALUE - remaining) invalid()
    return RouterProviderResolution(addresses, receivedAt + remaining)
}
