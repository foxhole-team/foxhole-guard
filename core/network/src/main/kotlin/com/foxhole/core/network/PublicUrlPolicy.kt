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
        require(addresses.none { it.isPrivateOrLocalAddress() }) { "private or loopback hosts are not allowed" }
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
