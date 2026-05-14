package com.foxhole.beta.core.network

import java.util.Locale

fun normalizeDnsDomainRules(raw: String): List<String> =
    raw
        .split('\n', ',', ';')
        .mapNotNull(::normalizeDnsDomainRule)
        .distinct()

fun normalizeDnsDomainRule(raw: String): String? {
    val normalized =
        raw
            .trim()
            .removePrefix("*.")
            .removePrefix(".")
            .removeSuffix(".")
            .lowercase(Locale.US)
    if (normalized.isBlank() || normalized.length > MAX_DOMAIN_LENGTH) {
        return null
    }
    val labels = normalized.split('.')
    if (labels.any { label -> !label.isValidDnsLabel() }) {
        return null
    }
    return normalized
}

private fun String.isValidDnsLabel(): Boolean =
    isNotBlank() &&
        length <= MAX_DNS_LABEL_LENGTH &&
        first() != '-' &&
        last() != '-' &&
        all { char -> char in 'a'..'z' || char in '0'..'9' || char == '-' }

private const val MAX_DOMAIN_LENGTH = 253
private const val MAX_DNS_LABEL_LENGTH = 63
