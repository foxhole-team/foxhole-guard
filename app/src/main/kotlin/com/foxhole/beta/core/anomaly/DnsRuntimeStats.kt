package com.foxhole.beta.core.anomaly

import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class DnsRuntimeDelta(
    val blocked: Int,
    val allowed: Int,
)

object DnsRuntimeStats {
    private val blockedQueries = AtomicInteger()
    private val allowedQueries = AtomicInteger()
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
        val normalized = message.trim().lowercase(Locale.US)
        when {
            normalized.startsWith("rejected ") ||
                " rejected " in normalized ->
                blockedQueries.incrementAndGet()
            normalized.startsWith("exchanged ") ||
                normalized.startsWith("cached ") ->
                allowedQueries.incrementAndGet()
        }
    }

    fun drain(): DnsRuntimeDelta =
        DnsRuntimeDelta(
            blocked = blockedQueries.getAndSet(0),
            allowed = allowedQueries.getAndSet(0),
        )

    fun snapshot(): DnsRuntimeDelta =
        DnsRuntimeDelta(
            blocked = blockedQueries.get(),
            allowed = allowedQueries.get(),
        )

    fun reset() {
        blockedQueries.set(0)
        allowedQueries.set(0)
        seenDnsConnectionIds.clear()
    }
}
