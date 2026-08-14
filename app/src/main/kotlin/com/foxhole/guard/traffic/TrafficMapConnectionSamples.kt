package com.foxhole.guard.traffic
import java.util.Locale

internal fun RuntimeConnectionSnapshot.toTrafficMapConnectionSamples(
    maxConnections: Int,
    countryCodeForDestination: (String) -> String?,
    ownPackageName: String? = null,
    dnsServerHost: () -> String? = { null },
    includeDirectOutbound: () -> Boolean = { false },
): List<TrafficMapConnectionSample> {
    val samples = mutableListOf<TrafficMapConnectionSample>()
    val directCountsAsEgress = includeDirectOutbound()
    connections
        .take(maxConnections)
        .forEach { connection ->
            if (connection.outboundType.equals(DNS_OUTBOUND_TYPE, ignoreCase = true)) {
                // The DNS resolver egress is its own map node (never an app destination): count
                // the query connection and resolve the resolver's country from the connection or
                // the configured DNS server.
                connection
                    .toTrafficMapDnsSample(countryCodeForDestination, dnsServerHost)
                    ?.let(samples::add)
            } else if (ownPackageName != null && connection.packageNames.contains(ownPackageName)) {
                // Our own sockets are the tunnel carrier to the VPN server — bytes already
                // represented by the session totals on the route node; counting them as an app
                // destination painted the VPN server's country into the apps table.
            } else {
                connection
                    .toTrafficMapConnectionSample(countryCodeForDestination, directCountsAsEgress)
                    ?.let(samples::add)
            }
        }
    return samples
}

private fun RuntimeConnectionRecord.toTrafficMapDnsSample(
    countryCodeForDestination: (String) -> String?,
    dnsServerHost: () -> String?,
): TrafficMapConnectionSample? {
    val destinationHost = destination.orEmpty().toRuntimeEndpoint().host
    val countryCode =
        sequenceOf(destinationHost, dnsServerHost().orEmpty())
            .map(String::trim)
            .filter(String::isNotEmpty)
            .firstNotNullOfOrNull { candidate ->
                countryCodeForDestination(candidate)
                    ?.takeIf { code -> code.length == 2 }
                    ?.uppercase(Locale.US)
            }
            ?: return null
    val bytes = maxOf(0L, bytesTx) + maxOf(0L, bytesRx)
    return TrafficMapConnectionSample(
        connectionId = connectionId,
        countryCode = countryCode,
        bytes = bytes,
        connections = 1,
        kind = TrafficMapConnectionSampleKind.DNS_SERVER,
    )
}

private fun RuntimeConnectionRecord.toTrafficMapConnectionSample(
    countryCodeForDestination: (String) -> String?,
    directCountsAsEgress: Boolean = false,
): TrafficMapConnectionSample? =
    runtimeConnectionTrafficMapSample(
        connectionId = connectionId,
        outboundType = outboundType,
        destination = destination,
        domain = domain,
        uplink = 0L,
        downlink = 0L,
        uplinkTotal = bytesTx,
        downlinkTotal = bytesRx,
        countryCodeForDestination = countryCodeForDestination,
        directCountsAsEgress = directCountsAsEgress,
    )

@Suppress("LongParameterList")
internal fun runtimeConnectionTrafficMapSample(
    connectionId: String,
    outboundType: String?,
    destination: String?,
    domain: String?,
    uplink: Long,
    downlink: Long,
    uplinkTotal: Long,
    downlinkTotal: Long,
    countryCodeForDestination: (String) -> String?,
    directCountsAsEgress: Boolean = false,
): TrafficMapConnectionSample? {
    if (isNonTunneledOutboundType(outboundType, directCountsAsEgress)) {
        // The map represents traffic that actually left through the observed egress. Under a VPN
        // tunnel, flows FoxCore routed to the `direct`/`block` outbound (split-tunnel
        // "direct" apps, private-IP/LAN direct rules, blocked flows) bypass the VPN and must not
        // be drawn as VPN nodes. In the local-guard firewall mode there is no tunnel and `direct`
        // IS the device egress — dropping it left the firewall map permanently empty, so the
        // caller marks direct as countable there.
        return null
    }
    val totalBytes =
        maxOf(0L, uplinkTotal) +
            maxOf(0L, downlinkTotal)
    val currentBytes =
        maxOf(0L, uplink) +
            maxOf(0L, downlink)
    val bytes = totalBytes.takeIf { value -> value > 0L } ?: currentBytes
    if (bytes <= 0L) {
        return null
    }
    val destinationHost = destination.orEmpty().toRuntimeEndpoint().host
    val countryCode =
        sequenceOf(destination, destinationHost, domain)
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .firstNotNullOfOrNull { candidate ->
                countryCodeForDestination(candidate)
                    ?.takeIf { code -> code.length == 2 }
                    ?.uppercase(Locale.US)
            }
            ?: return null
    return TrafficMapConnectionSample(
        connectionId = connectionId,
        countryCode = countryCode,
        bytes = bytes,
        connections = 1,
    )
}

private const val DNS_OUTBOUND_TYPE = "dns"
private const val DIRECT_OUTBOUND_TYPE = "direct"
private const val BLOCK_OUTBOUND_TYPE = "block"

// Outbound types that did not egress through the tunnel and must be excluded from the traffic map.
private val NON_TUNNELED_OUTBOUND_TYPES =
    setOf(DNS_OUTBOUND_TYPE, DIRECT_OUTBOUND_TYPE, BLOCK_OUTBOUND_TYPE)

internal fun isNonTunneledOutboundType(
    outboundType: String?,
    directCountsAsEgress: Boolean = false,
): Boolean {
    val normalized = outboundType?.trim()?.lowercase(Locale.US)
    if (directCountsAsEgress && normalized == DIRECT_OUTBOUND_TYPE) {
        return false
    }
    return normalized in NON_TUNNELED_OUTBOUND_TYPES
}
