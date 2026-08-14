package com.foxhole.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

typealias RemoteHostResolver = (String) -> List<InetAddress>

fun String.ensurePublicHttpsUrl(
    resolveHost: Boolean = false,
    resolver: RemoteHostResolver? = null,
): HttpUrl {
    val url = requireNotNull(trim().toHttpUrlOrNull()) { "invalid https url" }
    url.requirePublicHttpsUrl(resolveHost = resolveHost, resolver = resolver)
    return url
}

fun String.ensurePublicUrl(
    allowHttp: Boolean,
    resolveHost: Boolean = false,
    resolver: RemoteHostResolver? = null,
): HttpUrl {
    val url = requireNotNull(trim().toHttpUrlOrNull()) { "invalid url" }
    url.requirePublicUrl(allowHttp = allowHttp, resolveHost = resolveHost, resolver = resolver)
    return url
}

fun String.requirePublicRemoteHost(
    resolveHost: Boolean = false,
    resolver: RemoteHostResolver? = null,
): String {
    val normalized = trim().lowercase().trimEnd('.')
    require(normalized.isNotBlank()) { "remote host is required" }
    require(!normalized.isClearlyLocalHost()) { "private or loopback hosts are not allowed" }
    val addresses =
        when {
            normalized.isIpLiteral() ->
                listOfNotNull(
                    runCatching { InetAddress.getByName(normalized) }.getOrNull(),
                )
            resolveHost -> {
                val resolve = resolver ?: { hostname: String -> InetAddress.getAllByName(hostname).toList() }
                resolve(normalized)
            }
            else -> emptyList()
        }
    if (normalized.isIpLiteral()) {
        require(addresses.isNotEmpty()) { "invalid remote host" }
        require(addresses.none { it.isPrivateOrLocalAddress() }) { "private or loopback hosts are not allowed" }
    } else if (resolveHost) {
        require(addresses.isNotEmpty()) { "unable to resolve remote host" }
        // A synthesized answer is exempt, and only here — a literal in the pool is still refused
        // above. While a fake-IP tunnel is up EVERY name this device resolves is answered out of
        // the pool, so the address says nothing about where the host lives. Reading it as
        // "private" is what made the connect path reject the profile's own server and tear a live
        // tunnel down (Pixel, 2026-08-09: `session build failed ... UnknownHostException` followed
        // by VPN DISCONNECTED one second later).
        require(addresses.none { it.isPrivateOrLocalAddress() && !it.isTunnelSynthesizedAddress() }) {
            "private or loopback hosts are not allowed"
        }
    }
    return this
}

fun HttpUrl.requirePublicHttpsUrl(
    resolveHost: Boolean = false,
    resolver: RemoteHostResolver? = null,
): HttpUrl = requirePublicUrl(allowHttp = false, resolveHost = resolveHost, resolver = resolver)

fun HttpUrl.requirePublicUrl(
    allowHttp: Boolean,
    resolveHost: Boolean = false,
    resolver: RemoteHostResolver? = null,
): HttpUrl {
    require(isHttps || (allowHttp && scheme.equals("http", ignoreCase = true))) {
        if (allowHttp) {
            "only http and https endpoints are allowed"
        } else {
            "only https endpoints are allowed"
        }
    }
    host.requirePublicRemoteHost(resolveHost = resolveHost, resolver = resolver)
    return this
}

private fun String.isClearlyLocalHost(): Boolean {
    val normalized = lowercase().trimEnd('.')
    return normalized == "localhost" ||
        normalized.endsWith(".localhost") ||
        normalized.endsWith(".local") ||
        normalized.endsWith(".localdomain") ||
        normalized.endsWith(".lan") ||
        normalized.endsWith(".internal") ||
        normalized.endsWith(".home.arpa")
}

private fun String.isIpLiteral(): Boolean = contains(':') || IPV4_REGEX.matches(this)

/**
 * Whether this address is a placeholder FoxCore's own resolver invented for a name.
 *
 * The engine's fake-IP pools — `198.18.0.0/15` (RFC 2544) and `fc00::/18` — are handed out by the
 * in-tunnel DNS interceptor whenever an overlay (Tor / I2P) is armed, and the userspace stack turns
 * them back into the name on the way out. They are therefore *this device's own tunnel answering*,
 * not evidence about the host: while such a tunnel is up, every hostname the app resolves through
 * the system resolver lands in the pool, its own profile server included.
 *
 * Both ranges are also inside [isPrivateOrLocalAddress], which is correct for a literal a user
 * typed and wrong for a resolution result. Callers that classify *answers* must subtract this.
 */
fun InetAddress.isTunnelSynthesizedAddress(): Boolean =
    when (this) {
        is Inet4Address -> address.let { it.size == 4 && it[0].toUByte().toInt() == 198 && it[1].toUByte().toInt() in 18..19 }
        is Inet6Address ->
            address.let { it.size == 16 && it[0].toUByte().toInt() == 0xfc && it[1].toUByte().toInt() in 0x00..0x3f }
        else -> false
    }

@Suppress("ComplexCondition")
fun InetAddress.isPrivateOrLocalAddress(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
        return true
    }
    return when (this) {
        is Inet4Address -> address.isPrivateOrReservedIpv4()
        is Inet6Address -> address.isPrivateOrReservedIpv6()
        // Fail closed: an address family we cannot classify is never treated as public.
        else -> true
    }
}

@Suppress("CyclomaticComplexMethod")
private fun ByteArray.isPrivateOrReservedIpv4(): Boolean {
    if (size != 4) {
        return true
    }
    val first = this[0].toUByte().toInt()
    val second = this[1].toUByte().toInt()
    return first == 0 ||
        first == 10 ||
        first == 127 ||
        (first == 100 && second in 64..127) ||
        (first == 169 && second == 254) ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 0) ||
        (first == 192 && second == 168) ||
        (first == 198 && second in 18..19) ||
        first >= 224
}

private fun ByteArray.isPrivateOrReservedIpv6(): Boolean {
    if (size != 16) {
        return true
    }
    val first = this[0].toUByte().toInt()
    val second = this[1].toUByte().toInt()
    val isUnspecified = indices.all { this[it] == 0.toByte() }
    val isLoopback = (0 until 15).all { this[it] == 0.toByte() } && this[15] == 1.toByte()
    return isUnspecified ||
        isLoopback ||
        (first == 0xfc || first == 0xfd) ||
        (first == 0xfe && second in 0x80..0xbf) ||
        first == 0xff
}

private val IPV4_REGEX =
    Regex(
        pattern = """^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""",
    )
