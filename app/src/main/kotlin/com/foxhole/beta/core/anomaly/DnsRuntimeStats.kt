package com.foxhole.beta.core.anomaly

import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class DnsRuntimeDelta(
    val blocked: Int,
    val allowed: Int,
    val blockedDomains: Map<String, Long> = emptyMap(),
)

object DnsRuntimeStats {
    private val blockedQueries = AtomicInteger()
    private val allowedQueries = AtomicInteger()
    private val blockedDomainQueries = ConcurrentHashMap<String, AtomicInteger>()
    private val seenDnsConnectionIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun recordDnsConnection(connectionId: String) {
        if (connectionId.isBlank()) {
            return
        }
        if (seenDnsConnectionIds.add(connectionId)) {
            allowedQueries.incrementAndGet()
        }
    }

    fun recordLogMessage(message: String) {
        val normalized = message.trim().lowercase(Locale.US).dnsRuntimePayload()
        val blockedDomain = normalized.blockedDnsRuntimeDomainOrNull()
        when {
            blockedDomain != null -> {
                blockedQueries.incrementAndGet()
                blockedDomainQueries.computeIfAbsent(blockedDomain) { AtomicInteger() }.incrementAndGet()
            }
            normalized.isFoxholeDnsRuleSetBlockLog() ->
                blockedQueries.incrementAndGet()
            normalized.isAllowedDnsRuntimeLog() ->
                allowedQueries.incrementAndGet()
        }
    }

    fun drain(): DnsRuntimeDelta =
        DnsRuntimeDelta(
            blocked = blockedQueries.getAndSet(0),
            allowed = allowedQueries.getAndSet(0),
            blockedDomains = drainBlockedDomains(),
        )

    fun snapshot(): DnsRuntimeDelta =
        DnsRuntimeDelta(
            blocked = blockedQueries.get(),
            allowed = allowedQueries.get(),
            blockedDomains = snapshotBlockedDomains(),
        )

    fun reset() {
        blockedQueries.set(0)
        allowedQueries.set(0)
        blockedDomainQueries.clear()
        seenDnsConnectionIds.clear()
    }

    private fun snapshotBlockedDomains(): Map<String, Long> =
        blockedDomainQueries.entries
            .asSequence()
            .mapNotNull { (domain, count) ->
                count.get().takeIf { it > 0 }?.let { domain to it.toLong() }
            }
            .toMap()

    private fun drainBlockedDomains(): Map<String, Long> {
        val snapshot = snapshotBlockedDomains()
        blockedDomainQueries.clear()
        return snapshot
    }
}

internal fun isDnsRuntimeLogMessage(message: String): Boolean {
    val normalized = message.trim().lowercase(Locale.US).dnsRuntimePayload()
    return normalized.isBlockedDnsRuntimeLog() || normalized.isAllowedDnsRuntimeLog()
}

private fun String.isBlockedDnsRuntimeLog(): Boolean =
    blockedDnsRuntimeDomainOrNull() != null || isFoxholeDnsRuleSetBlockLog()

private fun String.isAllowedDnsRuntimeLog(): Boolean {
    if (!startsWith("exchanged ") && !startsWith("cached ")) {
        return false
    }
    val tokens = split(DNS_LOG_TOKEN_SEPARATOR, limit = 4)
    return tokens.getOrNull(2)?.lowercase(Locale.US) != DNS_RCODE_NXDOMAIN
}

private fun String.blockedDnsRuntimeDomainOrNull(): String? {
    if (!startsWith("rejected ")) {
        return null
    }
    val tokens = removePrefix("rejected").trimStart().split(DNS_LOG_TOKEN_SEPARATOR, limit = 3)
    if (tokens.size < 2 || tokens[0] !in DNS_QUERY_TYPE_TOKENS) {
        return null
    }
    return tokens[1].normalizedDnsRuntimeDomainOrNull()
}

private fun String.isFoxholeDnsRuleSetBlockLog(): Boolean =
    (
        contains("rule_set=$DNS_ADGUARD_RULE_SET_TAG") ||
            contains("rule_set=[$DNS_ADGUARD_RULE_SET_TAG")
        ) &&
        contains("=> predefined($DNS_RCODE_NXDOMAIN)")

private fun String.dnsRuntimePayload(): String {
    val tagged = substringAfter(DNS_LOG_TAG_MARKER, this)
    return DNS_CONTEXT_PREFIX.replace(tagged.trim(), "")
}

private fun String.normalizedDnsRuntimeDomainOrNull(): String? {
    val normalized = trim().trimEnd('.').lowercase(Locale.US)
    if (normalized.isBlank() || normalized.length > MAX_DNS_DOMAIN_LENGTH) {
        return null
    }
    if (normalized.any { char -> !(char.isLetterOrDigit() || char == '-' || char == '_' || char == '.') }) {
        return null
    }
    return normalized
}

private val DNS_LOG_TOKEN_SEPARATOR = Regex("\\s+")
private val DNS_CONTEXT_PREFIX = Regex("""^\[[^]]+]\s+""")

private const val MAX_DNS_DOMAIN_LENGTH = 253
private const val DNS_ADGUARD_RULE_SET_TAG = "foxhole-adguard-dns-filter"
private const val DNS_LOG_TAG_MARKER = "dns: "
private const val DNS_RCODE_NXDOMAIN = "nxdomain"

private val DNS_QUERY_TYPE_TOKENS =
    setOf(
        "a",
        "aaaa",
        "caa",
        "cname",
        "dnskey",
        "ds",
        "https",
        "mx",
        "naptr",
        "ns",
        "ptr",
        "soa",
        "srv",
        "svcb",
        "txt",
    )
