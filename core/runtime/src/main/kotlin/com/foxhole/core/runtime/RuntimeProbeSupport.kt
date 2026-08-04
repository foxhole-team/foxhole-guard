package com.foxhole.core.runtime

import android.net.ConnectivityManager
import android.net.Network
import com.foxhole.core.model.CONNECTIVITY_PROBE_ENDPOINTS

// Self-contained runtime probe helpers, kept engine-side (out of the host connection
// controller) so the engine does not depend on it.
fun representativeLatencyMs(latenciesMs: List<Long>): Long? {
    val normalized = latenciesMs.map { it.coerceAtLeast(1L) }.sorted()
    if (normalized.isEmpty()) {
        return null
    }
    val middleIndex = normalized.size / 2
    return if (normalized.size % 2 == 1) {
        normalized[middleIndex]
    } else {
        ((normalized[middleIndex - 1] + normalized[middleIndex]) / 2L).coerceAtLeast(1L)
    }
}

fun ConnectivityManager.dnsServerAddresses(network: Network?): List<String> =
    network
        ?.let(::getLinkProperties)
        ?.dnsServers
        .orEmpty()
        .mapNotNull { it.hostAddress?.takeIf(String::isNotBlank) }
        .distinct()

internal val LATENCY_PROBE_ENDPOINTS = CONNECTIVITY_PROBE_ENDPOINTS

fun latencyProbeEndpoints(): List<String> = LATENCY_PROBE_ENDPOINTS
