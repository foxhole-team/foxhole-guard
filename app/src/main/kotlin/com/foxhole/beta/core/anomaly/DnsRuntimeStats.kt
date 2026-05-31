package com.foxhole.beta.core.anomaly

import java.util.ArrayDeque
import java.util.HashSet
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
    private val seenConnectionLock = Any()
    private val seenDnsConnectionIdOrder = ArrayDeque<String>()
    private val seenDnsConnectionIds = HashSet<String>()

    fun recordDnsConnection(connectionId: String) {
        if (connectionId.isBlank()) {
            return
        }
        if (recordSeenDnsConnectionId(connectionId)) {
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
        synchronized(seenConnectionLock) {
            seenDnsConnectionIdOrder.clear()
            seenDnsConnectionIds.clear()
        }
    }

    internal fun trackedConnectionIdCountForTests(): Int =
        synchronized(seenConnectionLock) {
            seenDnsConnectionIds.size
        }

    private fun recordSeenDnsConnectionId(connectionId: String): Boolean =
        synchronized(seenConnectionLock) {
            if (!seenDnsConnectionIds.add(connectionId)) {
                return@synchronized false
            }
            seenDnsConnectionIdOrder.addLast(connectionId)
            while (seenDnsConnectionIds.size > DNS_RUNTIME_CONNECTION_ID_WINDOW_SIZE) {
                val expired = seenDnsConnectionIdOrder.removeFirst()
                seenDnsConnectionIds.remove(expired)
            }
            true
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
    val tokens =
        takeIf { it.startsWith("rejected ") }
            ?.removePrefix("rejected")
            ?.trimStart()
            ?.split(DNS_LOG_TOKEN_SEPARATOR, limit = 3)
            .orEmpty()
    return tokens
        .getOrNull(1)
        ?.takeIf { tokens.getOrNull(0) in DNS_QUERY_TYPE_TOKENS }
        ?.normalizedDnsRuntimeDomainOrNull()
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
    val valid =
        normalized.isNotBlank() &&
            normalized.length <= MAX_DNS_DOMAIN_LENGTH &&
            normalized.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' }
    return normalized.takeIf { valid }
}

private val DNS_LOG_TOKEN_SEPARATOR = Regex("\\s+")
private val DNS_CONTEXT_PREFIX = Regex("""^\[[^]]+]\s+""")

private const val MAX_DNS_DOMAIN_LENGTH = 253
internal const val DNS_RUNTIME_CONNECTION_ID_WINDOW_SIZE = 4_096
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
