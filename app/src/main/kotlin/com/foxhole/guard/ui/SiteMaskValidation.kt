package com.foxhole.guard.ui

import com.foxhole.guard.R

internal fun normalizedSiteMaskToken(value: String): String? {
    val normalized = value.trim().takeIf(String::isNotBlank) ?: return null
    return when {
        normalized.startsWith("cidr:") ||
            normalized.startsWith("kw:") ||
            normalized.startsWith("re:") ||
            normalized.startsWith("*.") -> normalized
        normalized.startsWith(".") -> "*.${normalized.removePrefix(".")}"
        else -> normalized
    }
}

internal fun siteMaskValidationErrorRes(value: String): Int? {
    val token = normalizedSiteMaskToken(value) ?: return R.string.site_exception_validation_error
    return if (isValidSiteMaskToken(token)) null else R.string.site_exception_invalid_error
}

private fun isValidSiteMaskToken(token: String): Boolean =
    when {
        token.startsWith("cidr:") -> isValidCidr(token.removePrefix("cidr:"))
        token.startsWith("kw:") -> isValidKeywordMask(token.removePrefix("kw:"))
        token.startsWith("re:") -> isValidRegexMask(token.removePrefix("re:"))
        token.startsWith("*.") -> isValidDomainName(token.removePrefix("*."), minimumLabels = 1)
        else -> isValidDomainName(token)
    }

private fun isValidKeywordMask(value: String): Boolean =
    value.isNotBlank() && value.none { it.isWhitespace() } && "," !in value

private fun isValidRegexMask(value: String): Boolean =
    value.isNotBlank() &&
        runCatching { Regex(value) }.isSuccess

private fun isValidCidr(value: String): Boolean {
    val parts = value.split("/", limit = 2)
    val address = parts[0]
    val prefix = parts.getOrNull(1)?.toIntOrNull()
    return when {
        parts.size != 2 || prefix == null -> false
        ":" in address ->
            prefix in 0..128 &&
                runCatching {
                    java.net.InetAddress.getByName(address) is java.net.Inet6Address
                }.getOrDefault(false)
        else -> prefix in 0..32 && isValidIpv4Address(address)
    }
}

private fun isValidIpv4Address(value: String): Boolean {
    val segments = value.split(".")
    return segments.size == 4 &&
        segments.all { segment ->
            segment.isNotEmpty() &&
                segment.all(Char::isDigit) &&
                segment.toIntOrNull()?.let { it in 0..255 } == true
        }
}

private fun isValidDomainName(
    value: String,
    minimumLabels: Int = 2,
): Boolean {
    val domain = value.trim().removeSuffix(".")
    return when {
        domain.length !in 1..253 || domain.contains("..") -> false
        domain.any { it.isWhitespace() || it in "/:@," } -> false
        else -> {
            val labels = domain.split(".")
            labels.size >= minimumLabels && labels.all(::isValidDomainLabel)
        }
    }
}

private fun isValidDomainLabel(value: String): Boolean =
    value.length in 1..63 &&
        value.first().isLetterOrDigit() &&
        value.last().isLetterOrDigit() &&
        value.all { it.isLetterOrDigit() || it == '-' }
