package com.foxhole.core.model

import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class DnsRuntimeDelta(
    val blocked: Int,
    val allowed: Int,
    val blockedDomains: Map<String, Long> = emptyMap(),

    val blockedByCategory: Map<DnsFilterCategory, Int> = emptyMap(),

    val blockedByApp: Map<String, Int> = emptyMap(),
)

object DnsRuntimeStats {
    private val blockedQueries = AtomicInteger()
    private val allowedQueries = AtomicInteger()
    private val blockedDomainQueries = ConcurrentHashMap<String, AtomicInteger>()
    private val blockedCategoryQueries = ConcurrentHashMap<DnsFilterCategory, AtomicInteger>()
    private val blockedAppQueries = ConcurrentHashMap<String, AtomicInteger>()
    private val observationLock = Any()
    private val dnsQueryObservations = ArrayDeque<DnsQueryObservation>()

    fun recordDnsVerdictDelta(
        blocked: Long,
        allowed: Long,
    ) {
        blockedQueries.addSaturating(blocked)
        allowedQueries.addSaturating(allowed)
    }

    fun recordDnsQueryObservation(
        domain: String,
        packageNames: List<String>,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val normalizedDomain = domain.normalizedDnsRuntimeDomainOrNull() ?: return
        val normalizedPackages = packageNames.map(String::trim).filter(String::isNotBlank)
        if (normalizedPackages.isEmpty()) {
            return
        }
        synchronized(observationLock) {
            dnsQueryObservations.removeAll { observation -> observation.domain == normalizedDomain }
            dnsQueryObservations.addLast(
                DnsQueryObservation(
                    domain = normalizedDomain,
                    packageNames = normalizedPackages,
                    timestampMs = timestampMs,
                ),
            )
            while (dnsQueryObservations.size > DNS_QUERY_OBSERVATION_WINDOW_SIZE) {
                dnsQueryObservations.removeFirst()
            }
        }
    }

    fun recordBlockedDnsBreakdown(
        domain: String,
        category: DnsFilterCategory?,
        packageName: String?,
    ) {
        val normalizedDomain = domain.normalizedDnsRuntimeDomainOrNull() ?: return

        if (blockedDomainQueries.size < MAX_TRACKED_BLOCKED_DOMAINS ||
            blockedDomainQueries.containsKey(normalizedDomain)
        ) {
            blockedDomainQueries
                .computeIfAbsent(normalizedDomain) { AtomicInteger() }
                .addSaturating(1L)
        }
        category
            ?.let { blockedCategoryQueries.computeIfAbsent(it) { AtomicInteger() } }
            ?.addSaturating(1L)
        val directPackage = packageName?.trim()?.takeIf(String::isNotBlank)
        if (directPackage != null) {
            blockedAppQueries
                .computeIfAbsent(directPackage) { AtomicInteger() }
                .addSaturating(1L)
        } else {
            attributeBlockedDomainToApps(normalizedDomain)
        }
    }

    fun drain(): DnsRuntimeDelta =
        DnsRuntimeDelta(
            blocked = blockedQueries.getAndSet(0),
            allowed = allowedQueries.getAndSet(0),
            blockedDomains = drainBlockedDomains(),
            blockedByCategory = drainBlockedCategories(),
            blockedByApp = drainBlockedApps(),
        )

    fun snapshot(): DnsRuntimeDelta =
        DnsRuntimeDelta(
            blocked = blockedQueries.get(),
            allowed = allowedQueries.get(),
            blockedDomains = snapshotBlockedDomains(),
            blockedByCategory = snapshotBlockedCategories(),
            blockedByApp = snapshotBlockedApps(),
        )

    fun reset() {
        blockedQueries.set(0)
        allowedQueries.set(0)
        blockedDomainQueries.clear()
        blockedCategoryQueries.clear()
        blockedAppQueries.clear()
        synchronized(observationLock) {
            dnsQueryObservations.clear()
        }
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

    private fun snapshotBlockedCategories(): Map<DnsFilterCategory, Int> =
        blockedCategoryQueries.entries
            .asSequence()
            .mapNotNull { (category, count) ->
                count.get().takeIf { it > 0 }?.let { category to it }
            }
            .toMap()

    private fun drainBlockedCategories(): Map<DnsFilterCategory, Int> {
        val snapshot = snapshotBlockedCategories()
        blockedCategoryQueries.clear()
        return snapshot
    }

    private fun attributeBlockedDomainToApps(domain: String) {
        val packages =
            synchronized(observationLock) {
                val nowMs = System.currentTimeMillis()
                while (dnsQueryObservations.isNotEmpty() &&
                    nowMs - dnsQueryObservations.first().timestampMs > DNS_QUERY_OBSERVATION_TTL_MS
                ) {
                    dnsQueryObservations.removeFirst()
                }
                dnsQueryObservations.lastOrNull { observation -> observation.domain == domain }?.packageNames
            } ?: return
        packages.forEach { packageName ->
            blockedAppQueries
                .computeIfAbsent(packageName) { AtomicInteger() }
                .addSaturating(1L)
        }
    }

    private fun snapshotBlockedApps(): Map<String, Int> =
        blockedAppQueries.entries
            .asSequence()
            .mapNotNull { (packageName, count) ->
                count.get().takeIf { it > 0 }?.let { packageName to it }
            }
            .toMap()

    private fun drainBlockedApps(): Map<String, Int> {
        val snapshot = snapshotBlockedApps()
        blockedAppQueries.clear()
        return snapshot
    }

    private data class DnsQueryObservation(
        val domain: String,
        val packageNames: List<String>,
        val timestampMs: Long,
    )
}

private fun String.normalizedDnsRuntimeDomainOrNull(): String? {
    val normalized = trim().trimEnd('.').lowercase(Locale.US)
    val valid =
        normalized.isNotBlank() &&
            normalized.length <= MAX_DNS_DOMAIN_LENGTH &&
            normalized.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' }
    return normalized.takeIf { valid }
}

private fun AtomicInteger.addSaturating(delta: Long) {
    if (delta <= 0L) {
        return
    }
    updateAndGet { current ->
        (current.toLong() + delta)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }
}

private const val MAX_DNS_DOMAIN_LENGTH = 253
const val MAX_TRACKED_BLOCKED_DOMAINS = 4_096
const val DNS_QUERY_OBSERVATION_WINDOW_SIZE = 2_048
const val DNS_QUERY_OBSERVATION_TTL_MS = 90_000L
