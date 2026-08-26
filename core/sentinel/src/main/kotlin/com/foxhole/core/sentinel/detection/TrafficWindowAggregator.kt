package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.model.NetworkType
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.VpnMode
import java.util.Locale

data class TrafficAggregationContext(
    val connection: ConnectionSnapshot,
    val settings: Settings,
    val networkType: NetworkType,
    val latencyMs: Int? = null,
    val destinationCountries: Map<String, Long> = emptyMap(),
    val reconnects: Int = 0,
    val blockedDns: Int = 0,
    val allowedDns: Int = 0,
    val blockedDnsDomains: Map<String, Long> = emptyMap(),
    val blockedDnsByCategory: Map<DnsFilterCategory, Long> = emptyMap(),
    val blockedDnsApps: Map<String, Long> = emptyMap(),
)

class TrafficWindowAggregator(
    private val minWindowDurationMs: Long = DEFAULT_WINDOW_DURATION_MS,
) {
    private var lastSnapshot: TrafficSnapshot? = null
    private var lastConnectionState: ConnectionState? = null
    private var lastDestinationCountries: Map<String, Long>? = null
    private var reconnectsInWindow: Int = 0

    fun reset() {
        lastSnapshot = null
        lastConnectionState = null
        lastDestinationCountries = null
        reconnectsInWindow = 0
    }

    fun aggregate(
        snapshot: TrafficSnapshot,
        context: TrafficAggregationContext,
    ): TrafficWindow? {
        if (!snapshot.available) {
            return null
        }
        val previousState = lastConnectionState
        if (previousState == ConnectionState.CONNECTED && context.connection.state == ConnectionState.RECONNECTING) {
            reconnectsInWindow += 1
        }
        lastConnectionState = context.connection.state

        val previous = lastSnapshot
        if (previous == null || snapshot.rxTotalBytes < previous.rxTotalBytes || snapshot.txTotalBytes < previous.txTotalBytes) {
            storeBaseline(snapshot, context.destinationCountries)
            return null
        }
        val elapsedMs = snapshot.sampledAt - previous.sampledAt
        if (elapsedMs < minWindowDurationMs) {
            return null
        }
        lastSnapshot = snapshot
        val currentDestinationCountries = context.destinationCountries.sanitizedCountryBytes()
        val destinationCountryDeltas = countryByteDeltas(lastDestinationCountries, currentDestinationCountries)
        lastDestinationCountries = currentDestinationCountries
        val durationSec = (elapsedMs / 1000L).toInt().coerceAtLeast(1)
        val window =
            TrafficWindow(
                startedAtMs = previous.sampledAt,
                durationSec = durationSec,
                networkType = context.networkType,
                vpnMode = context.settings.toVpnMode(),
                profileId = context.connection.profileId?.toString(),
                protocol = context.connection.protocolHint?.name?.lowercase(Locale.US),
                rxBytes = (snapshot.rxTotalBytes - previous.rxTotalBytes).coerceAtLeast(0L),
                txBytes = (snapshot.txTotalBytes - previous.txTotalBytes).coerceAtLeast(0L),
                blockedDns = context.blockedDns.coerceAtLeast(0),
                allowedDns = context.allowedDns.coerceAtLeast(0),
                reconnects = reconnectsInWindow.coerceAtLeast(context.reconnects),
                latencyMs = context.latencyMs?.takeIf { it > 0 },
                destinationCountries = destinationCountryDeltas,
                blockedDnsDomains = context.blockedDnsDomains.sanitizedDnsDomainCounts(),
                blockedDnsByCategory =
                context.blockedDnsByCategory.filterValues { count -> count > 0L },
                blockedDnsApps = context.blockedDnsApps.filterValues { count -> count > 0L },
            )
        reconnectsInWindow = 0
        return window
    }

    private fun storeBaseline(
        snapshot: TrafficSnapshot,
        destinationCountries: Map<String, Long>,
    ) {
        lastSnapshot = snapshot
        lastDestinationCountries = destinationCountries.sanitizedCountryBytes()
    }

    companion object {
        const val DEFAULT_WINDOW_DURATION_MS = 60_000L
    }
}

private fun Settings.toVpnMode(): VpnMode =
    when {
        privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN -> VpnMode.TOR
        expert.perAppRoutingMode.name.contains("BYPASS", ignoreCase = true) -> VpnMode.DIRECT_BYPASS
        else -> VpnMode.NORMAL
    }

private fun Map<String, Long>.sanitizedCountryBytes(): Map<String, Long> =
    entries
        .asSequence()
        .mapNotNull { (country, bytes) ->
            val normalized = country.trim().uppercase(Locale.US)
            if (normalized.length == 2 && normalized.all { it in 'A'..'Z' } && bytes > 0L) {
                normalized to bytes
            } else {
                null
            }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.sum() }

internal fun countryByteDeltas(
    previous: Map<String, Long>?,
    current: Map<String, Long>,
): Map<String, Long> =
    current
        .mapValues { (country, bytes) -> bytes - (previous?.get(country) ?: 0L) }
        .filterValues { bytes -> bytes > 0L }

private fun Map<String, Long>.sanitizedDnsDomainCounts(): Map<String, Long> =
    entries
        .asSequence()
        .mapNotNull { (domain, count) ->
            val normalized = domain.trim().trimEnd('.').lowercase(Locale.US)
            if (isValidDnsDomainCount(normalized, count)) {
                normalized to count
            } else {
                null
            }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, counts) -> counts.sum() }

private fun isValidDnsDomainCount(
    normalizedDomain: String,
    count: Long,
): Boolean =
    count > 0L &&
        normalizedDomain.isNotBlank() &&
        normalizedDomain.length <= MAX_DNS_DOMAIN_LENGTH &&
        normalizedDomain.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' }

private const val MAX_DNS_DOMAIN_LENGTH = 253
