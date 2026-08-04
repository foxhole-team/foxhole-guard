package com.foxhole.guard.traffic

import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.TrafficMapCountryDetail
import com.foxhole.core.model.TrafficWindow
import java.util.Locale

// Sample-window aggregation: day/7-day/window rollups and network-activity details.
// Split from TrafficMapAggregation.kt.

internal fun aggregateTrafficMapSamples(
    samples: List<TrafficMapConnectionSample>,
): Map<String, TrafficMapAggregate> {
    val next = linkedMapOf<String, TrafficMapAggregate>()
    samples.forEach { sample ->
        val countryCode = sample.countryCode.uppercase(Locale.US)
        val current = next[countryCode]
        next[countryCode] =
            TrafficMapAggregate(
                countryCode = countryCode,
                bytes = (current?.bytes ?: 0L) + sample.bytes.coerceAtLeast(0L),
                connections = (current?.connections ?: 0) + sample.connections.coerceAtLeast(0),
            )
    }
    return next
}

internal fun trafficMapDayAggregates(
    trafficWindows: List<TrafficWindow>,
    nowMs: Long = System.currentTimeMillis(),
): Map<String, TrafficMapAggregate> =
    trafficMapWindowAggregates(
        trafficWindows = trafficWindows,
        nowMs = nowMs,
        windowMs = TRAFFIC_MAP_DAY_24_MS,
    )

internal fun trafficMapSevenDayAggregates(
    trafficWindows: List<TrafficWindow>,
    nowMs: Long = System.currentTimeMillis(),
): Map<String, TrafficMapAggregate> =
    trafficMapWindowAggregates(
        trafficWindows = trafficWindows,
        nowMs = nowMs,
        windowMs = TRAFFIC_MAP_DAYS_7_MS,
    )

private fun trafficMapWindowAggregates(
    trafficWindows: List<TrafficWindow>,
    nowMs: Long,
    windowMs: Long,
): Map<String, TrafficMapAggregate> {
    val cutoffMs = nowMs - windowMs
    val bytesByCountry = linkedMapOf<String, Long>()
    val connectionCountsByCountry = linkedMapOf<String, Int>()
    trafficWindows
        .asSequence()
        .filter { window -> window.startedAtMs >= cutoffMs }
        .forEach { window ->
            window.destinationCountries.forEach { (rawCountryCode, rawBytes) ->
                val countryCode =
                    normalizeTrafficMapAggregateCountryCode(rawCountryCode)
                        ?: rawCountryCode.trim().uppercase(Locale.US)
                val bytes = rawBytes.coerceAtLeast(0L)
                if (bytes > 0L) {
                    bytesByCountry[countryCode] = (bytesByCountry[countryCode] ?: 0L) + bytes
                    connectionCountsByCountry[countryCode] = (connectionCountsByCountry[countryCode] ?: 0) + 1
                }
            }
        }
    return bytesByCountry.mapValues { (countryCode, bytes) ->
        TrafficMapAggregate(
            countryCode = countryCode,
            bytes = bytes,
            connections = connectionCountsByCountry[countryCode] ?: 0,
        )
    }
}

internal fun trafficMapCountryDetailsFromNetworkActivity(
    events: List<NetworkActivityEvent>,
    appLimit: Int = TrafficMapRepository.MaxTrafficMapCountryDetailRows,
    hostLimit: Int = TrafficMapRepository.MaxTrafficMapCountryDetailRows,
    includeHostDetails: Boolean = true,
    ownPackageNames: Set<String> = emptySet(),
): Map<String, TrafficMapCountryDetail> {
    if (events.isEmpty()) {
        return emptyMap()
    }
    val countries = linkedMapOf<String, MutableTrafficMapCountryDetail>()
    events.forEach { event ->
        val countryCode = normalizeTrafficMapAggregateCountryCode(event.countryCode) ?: return@forEach
        val bytes = event.totalBytes.coerceAtLeast(0L)
        if (bytes <= 0L) {
            return@forEach
        }
        val country =
            countries.getOrPut(countryCode) {
                MutableTrafficMapCountryDetail(countryCode)
            }
        country.observe(event.timestampMs)
        // Our own sockets are the tunnel carrier to the VPN server: attributing them as an "app"
        // painted FoxHole itself into the VPN server country's apps column.
        val packageNames =
            event.packageNames
                .normalizedTrafficMapPackageNames()
                .filterNot { packageName -> packageName in ownPackageNames }
        val appBytes = trafficMapSplitBytes(bytes, packageNames.size)
        packageNames.forEachIndexed { index, packageName ->
            val packageBytes = appBytes.getOrElse(index) { 0L }
            if (packageBytes > 0L) {
                country.appRows
                    .getOrPut(packageName) { MutableTrafficMapCountryAppAccumulator(packageName) }
                    .add(bytes = packageBytes, timestampMs = event.timestampMs)
            }
        }
        val remoteHost =
            if (includeHostDetails) {
                event.remoteHost.trim().takeIf(String::isNotEmpty)
            } else {
                null
            }
        if (remoteHost != null) {
            val key =
                TrafficMapCountryHostKey(
                    remoteHost = remoteHost,
                    remotePort = event.remotePort,
                    protocol = event.protocol.normalizedTrafficMapProtocol(),
                )
            country.hostRows
                .getOrPut(key) { MutableTrafficMapCountryHostAccumulator(key) }
                .add(bytes = bytes, timestampMs = event.timestampMs, packageNames = packageNames)
        }
    }
    return countries.mapValues { (_, country) ->
        country.toDetail(appLimit = appLimit, hostLimit = hostLimit)
    }
}

internal fun TrafficMapConnectionSample.deltaBytes(previous: TrafficMapConnectionSample?): Long {
    val currentBytes = bytes.coerceAtLeast(0L)
    val previousBytes = previous?.bytes?.coerceAtLeast(0L) ?: return currentBytes
    return if (currentBytes >= previousBytes) {
        currentBytes - previousBytes
    } else {
        currentBytes
    }
}
