package com.foxhole.beta.core.anomaly

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.NetworkType
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.VpnMode
import com.foxhole.beta.core.model.TrafficWindow
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
            lastSnapshot = snapshot
            lastDestinationCountries = context.destinationCountries.sanitizedCountryBytes()
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
            )
        reconnectsInWindow = 0
        return window
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
