package com.foxhole.guard.traffic
import java.util.Locale

internal fun RuntimeConnectionSnapshot.toTrafficMapConnectionSamples(
    maxConnections: Int,
    countryCodeForDestination: (String) -> String?,
    ownPackageName: String? = null,
    includeDirectOutbound: () -> Boolean = { false },
): List<TrafficMapConnectionSample> {
    val samples = mutableListOf<TrafficMapConnectionSample>()
    val directCountsAsEgress = includeDirectOutbound()
    connections
        .take(maxConnections)
        .forEach { connection ->
            if (connection.outboundType.equals(DNS_OUTBOUND_TYPE, ignoreCase = true)) {
                connection
                    .toTrafficMapDnsSample(countryCodeForDestination)
                    ?.let(samples::add)
            } else if (ownPackageName != null && connection.packageNames.contains(ownPackageName)) {
                return@forEach
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
): TrafficMapConnectionSample? {
    val destinationHost = destination.orEmpty().toRuntimeEndpoint().host
    val countryCode =
        sequenceOf(destinationHost)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .firstNotNullOfOrNull { candidate ->
                countryCodeForDestination(candidate)
                    ?.takeIf { code -> code.length == 2 }
                    ?.uppercase(Locale.US)
            }
            ?: return null
    val bytes = maxOf(0L, bytesTx) + maxOf(0L, bytesRx)
    if (bytes <= 0L) return null
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
