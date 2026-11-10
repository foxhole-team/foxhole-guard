package com.foxhole.guard.ui

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.math.BigInteger
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun normalizeSiteAddressMask(value: String): String? = runCatching {
    if (value.length > 2_048) return null
    var mask = value.trim().takeIf { it.isNotEmpty() && it.length <= 2_048 } ?: return null
    val explicitRange = mask.startsWith("range:", ignoreCase = true)
    if (explicitRange) mask = mask.substring(6)
    if ('-' in mask) {
        val ends = mask.split('-').map(String::trim)
        if (ends.size == 2) {
            val first = siteIpLiteral(ends[0])
            val last = siteIpLiteral(ends[1])
            if (first != null || last != null) {
                if (first == null || last == null || first.size != last.size || BigInteger(1, first) > BigInteger(1, last)) return null
                return "range:${siteIpText(first)}-${siteIpText(last)}"
            }
        }
    }
    if (explicitRange) return null
    if (mask.any { it.isWhitespace() || it.isISOControl() }) return null
    if (mask.startsWith("cidr:", ignoreCase = true)) return normalizeSiteCidr(mask.substring(5))?.let { "cidr:$it" }
    if ('/' in mask && siteIpLiteral(mask.substringBefore('/')) != null)
        return normalizeSiteCidr(mask)?.let { "cidr:$it" }

    val prefix = mask.substringBefore(':').lowercase(Locale.ROOT)
    val explicitSuffix = prefix in setOf("domain", "suffix", "zone") && ':' in mask
    val exact = prefix == "full" && ':' in mask
    if (explicitSuffix || exact) mask = mask.substringAfter(':')
    else if (mask.contains("://") || mask.startsWith("//") || mask.any { it in "/?#" } ||
        (':' in mask && siteIpLiteral(mask) == null)) {
        val url = URL(if (mask.startsWith("//")) "https:$mask" else if (mask.contains("://")) mask else "https://$mask")
        url.toURI()
        if (url.protocol !in setOf("http", "https") || url.userInfo != null ||
            (url.port != -1 && url.port !in 1..65_535) || url.authority.endsWith(':') || url.host.isEmpty()) return null
        mask = url.host.removeSurrounding("[", "]")
    }
    if (explicitSuffix || exact) {
        if (siteIpLiteral(mask) != null || mask.any { it in "/:@?#" }) return null
    }
    if (':' in mask) return normalizeSiteCidr("$mask/128")?.let { "cidr:$it" }
    if (mask.all { it in '0'..'9' || it == '.' }) return if (siteIpv4(mask)) "cidr:$mask/32" else null
    val suffix = explicitSuffix || mask.startsWith("*.") || mask.startsWith("+.") || mask.startsWith('.')
    if (exact && suffix) return null
    val domain = mask.removePrefix("*.").removePrefix("+.").removePrefix(".").removeSuffix(".")
    if (domain.any { it.isWhitespace() || it in "/:@,*" }) return null
    IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES)
    val ascii = ("https://$domain").toHttpUrlOrNull()?.host ?: return null
    val labels = ascii.split('.')
    if (ascii.length !in 1..253 || labels.last().all(Char::isDigit) || (exact && labels.size < 2) || labels.any { label ->
        label.length !in 1..63 || !label.first().isLetterOrDigit() || !label.last().isLetterOrDigit() ||
            label.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-') }
    }) return null
    if (suffix || labels.size == 1) "*.$ascii" else ascii
}.getOrNull()

private fun normalizeSiteCidr(raw: String): String? {
    val parts = raw.split('/')
    if (parts.size != 2) return null
    val prefix = parts[1].toIntOrNull() ?: return null
    val bytes = siteIpLiteral(parts[0]) ?: return null
    val bits = bytes.size * 8
    if (prefix !in 0..bits) return null
    val network = BigInteger(1, bytes).shiftRight(bits - prefix).shiftLeft(bits - prefix)
    return "${siteIpText(network, bytes.size)}/$prefix"
}

private fun siteIpLiteral(value: String): ByteArray? = when {
    ':' in value && value.all { it in "0123456789abcdefABCDEF:." } ->
        (runCatching { InetAddress.getByName(value) }.getOrNull() as? Inet6Address)?.address
    siteIpv4(value) -> value.split('.').map { it.toInt().toByte() }.toByteArray()
    else -> null
}

private fun siteIpText(bytes: ByteArray): String = InetAddress.getByAddress(bytes).hostAddress!!

private fun siteIpText(value: BigInteger, size: Int): String {
    val raw = value.toByteArray()
    val bytes = ByteArray(size)
    val count = minOf(raw.size, size)
    raw.copyInto(bytes, size - count, raw.size - count)
    return siteIpText(bytes)
}

internal fun siteAddressRangeCidrs(mask: String): List<String> {
    require(mask.startsWith("range:"))
    val ends = mask.removePrefix("range:").split('-')
    require(ends.size == 2)
    val first = requireNotNull(siteIpLiteral(ends[0]))
    val last = requireNotNull(siteIpLiteral(ends[1]))
    require(first.size == last.size)
    var start = BigInteger(1, first)
    val end = BigInteger(1, last)
    require(start <= end)
    val bits = first.size * 8
    return buildList {
        while (start <= end) {
            val aligned = if (start.signum() == 0) bits else start.lowestSetBit
            val remaining = end.subtract(start).add(BigInteger.ONE).bitLength() - 1
            val hostBits = minOf(aligned, remaining)
            add("${siteIpText(start, first.size)}/${bits - hostBits}")
            start = start.add(BigInteger.ONE.shiftLeft(hostBits))
        }
    }
}

private fun siteIpv4(value: String): Boolean = value.split('.').let { parts ->
    parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } &&
        (part.length == 1 || !part.startsWith('0')) && part.toIntOrNull() in 0..255 }
}
