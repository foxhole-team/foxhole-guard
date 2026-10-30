package com.foxhole.core.sentinel.detection

internal fun normalizeIpLiteral(raw: String): String? {
    val value = raw.trim().lowercase()
    if (value.isEmpty() || value.length > 45 || '%' in value) return null
    return if (':' in value) {
        parseIpv6(value)?.let(::compressIpv6)
    } else {
        ipv4Octets(value)?.joinToString(".")
    }
}

private fun parseIpv6(value: String): List<Int>? {
    if (value.any { it !in "0123456789abcdef:." }) return null
    val halves = value.split("::")
    if (halves.size > 2 || (halves.size == 2 && '.' in halves[0])) return null
    val left = ipv6Words(halves[0]) ?: return null
    return if (halves.size == 2) {
        ipv6Words(halves[1])?.let { right -> expandIpv6(left, right) }
    } else {
        left.takeIf { it.size == 8 }
    }
}

private fun expandIpv6(left: List<Int>, right: List<Int>): List<Int>? {
    val missing = 8 - left.size - right.size
    return if (missing > 0) left + List(missing) { 0 } + right else null
}

private fun ipv4Octets(value: String): List<Int>? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    val octets = parts.mapNotNull(::ipv4Octet)
    return octets.takeIf { it.size == 4 }
}

private fun ipv4Octet(part: String): Int? {
    if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
    if (part.length > 1 && part[0] == '0') return null
    return part.toIntOrNull()?.takeIf { it in 0..255 }
}

private fun ipv6Words(value: String): List<Int>? {
    if (value.isEmpty()) return emptyList()
    val tokens = value.split(':')
    val result = mutableListOf<Int>()
    tokens.forEachIndexed { index, token ->
        val words = ipv6Token(token, index == tokens.lastIndex) ?: return null
        result += words
    }
    return result.takeIf { it.size <= 8 }
}

private fun ipv6Token(token: String, isLast: Boolean): List<Int>? =
    if ('.' in token) {
        if (isLast) ipv4Octets(token)?.let(::ipv4Words) else null
    } else {
        ipv6Hextet(token)?.let(::listOf)
    }

private fun ipv4Words(octets: List<Int>): List<Int> = listOf(
    (octets[0] shl 8) or octets[1],
    (octets[2] shl 8) or octets[3],
)

private fun ipv6Hextet(token: String): Int? {
    if (token.isEmpty() || token.length > 4 || token.any { it !in "0123456789abcdef" }) return null
    return token.toIntOrNull(16)
}

private fun compressIpv6(words: List<Int>): String {
    var bestStart = -1
    var bestLength = 1
    var index = 0
    while (index < words.size) {
        if (words[index] != 0) {
            index += 1
            continue
        }
        val start = index
        while (index < words.size && words[index] == 0) index += 1
        if (index - start > bestLength) {
            bestStart = start
            bestLength = index - start
        }
    }
    if (bestStart < 0) return words.joinToString(":") { it.toString(16) }
    val before = words.take(bestStart).joinToString(":") { it.toString(16) }
    val after = words.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
    return "$before::$after"
}
