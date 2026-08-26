package com.foxhole.guard.core.data

import kotlinx.coroutines.flow.map
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

private val SUBSCRIPTION_SHARE_LINK_REGEX = Regex("""(?i)\b([a-z][a-z0-9+.-]*)://[^\s<>"']+""")

private val SUBSCRIPTION_SAFE_VALUE_KEYS =
    setOf(
        "security",
        "type",
        "flow",
        "fp",
        "fingerprint",
        "packetencoding",
        "packet_encoding",
        "packet-encoding",
    )

internal fun redactedSubscriptionPayloadShape(rawBody: String): String {
    val entries = mutableListOf<String>()
    val seen = linkedSetOf<String>()
    subscriptionPayloadCandidates(rawBody).forEach { (source, payload) ->
        SUBSCRIPTION_SHARE_LINK_REGEX.findAll(payload).take(5).forEach { match ->
            val link = match.value.trimEnd('.', ',', ';', ':', ')', ']', '}')
            val scheme = link.substringBefore("://", missingDelimiterValue = "unknown").lowercase()
            val query = link.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
            val queryShape = redactedQueryShape(query)
            val entry = "$source:$scheme $queryShape"
            if (seen.add(entry)) {
                entries += entry
            }
        }
    }
    val summary = entries.take(5).joinToString(separator = " | ")
    val suffix = if (entries.size > 5) " truncated=${entries.size - 5}" else ""
    return "subscription link shape entries=${entries.size}${if (summary.isBlank()) "" else " $summary"}$suffix"
}

private fun subscriptionPayloadCandidates(rawBody: String): List<Pair<String, String>> {
    val trimmed = rawBody.trim()
    if (trimmed.isBlank()) {
        return emptyList()
    }
    val candidates = mutableListOf("raw" to trimmed)
    decodeSubscriptionPayload(trimmed)?.let { decoded -> candidates += "base64" to decoded }
    return candidates.distinctBy { it.second }
}

private fun decodeSubscriptionPayload(rawBody: String): String? {
    val compact = rawBody.lineSequence().map(String::trim).filter(String::isNotBlank).joinToString(separator = "")
    if (compact.isBlank()) {
        return null
    }
    val normalized =
        compact
            .replace('-', '+')
            .replace('_', '/')
            .let { value ->
                val remainder = value.length % 4
                if (remainder == 0) value else value.padEnd(value.length + (4 - remainder), '=')
            }
    return runCatching {
        String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { decoded -> decoded.contains("://") }
}

private fun redactedQueryShape(rawQuery: String): String {
    if (rawQuery.isBlank()) {
        return "keys=none"
    }
    val keys = linkedSetOf<String>()
    val safeValues = mutableListOf<String>()
    rawQuery.split('&').forEach { item ->
        val rawKey = item.substringBefore('=').trim()
        if (rawKey.isBlank()) {
            return@forEach
        }
        val key = decodeQueryComponent(rawKey).trim()
        if (key.isBlank()) {
            return@forEach
        }
        keys += key
        val normalizedKey = key.lowercase()
        if (normalizedKey in SUBSCRIPTION_SAFE_VALUE_KEYS) {
            val rawValue = item.substringAfter('=', missingDelimiterValue = "").takeIf(String::isNotBlank)
            val value = rawValue?.let(::decodeQueryComponent)?.trim()?.lowercase()
            if (!value.isNullOrBlank()) {
                safeValues += "$normalizedKey=$value"
            }
        }
    }
    val keySummary = keys.take(20).joinToString(separator = ",").ifBlank { "none" }
    val valueSummary = safeValues.take(12).joinToString(separator = ",")
    return if (valueSummary.isBlank()) {
        "keys=$keySummary"
    } else {
        "keys=$keySummary values=$valueSummary"
    }
}

private fun decodeQueryComponent(value: String): String =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrElse { value }

internal suspend fun ProfileDao.setActiveProfileIfPresent(profileId: Long) {
    getById(profileId) ?: error("profile not found")
    clearActive()
    require(setActive(profileId) == 1) { "profile not found" }
}
