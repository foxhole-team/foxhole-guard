package com.foxhole.guard.ui

import android.content.Context
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.runtime.TorGeoIpCountryResolver
import com.foxhole.guard.statistics.countryDisplayName
import com.foxhole.guard.statistics.normalizedCountryCode

internal const val NETWORK_ACTIVITY_TAG = "activity"

internal fun networkActivityDiagnosticEntries(
    events: List<NetworkActivityEvent>,
    context: Context,
    countryResolver: TorGeoIpCountryResolver,
    ipInfo: IpInfo?,
): List<DiagnosticEntry> =
    events.map { event ->
        DiagnosticEntry(
            timestamp = event.timestampMs,
            tag = NETWORK_ACTIVITY_TAG,
            message = event.toNetworkActivityDiagnosticMessage(
                formatBytes = { bytes -> formatBytes(context, bytes) },
                countryCodeForDestination = countryResolver::countryCodeForDestination,
                ipInfo = ipInfo,
            ),
        )
    }

internal fun fallbackNetworkDiagnosticEntries(entries: List<DiagnosticEntry>): List<DiagnosticEntry> =
    entries.filter { it.tag == NETWORK_ACTIVITY_TAG }

internal fun NetworkActivityEvent.toNetworkActivityDiagnosticMessage(
    formatBytes: (Long) -> String,
    countryCodeForDestination: (String) -> String? = { null },
    ipInfo: IpInfo? = null,
): String =
    buildString {
        append("App connection: ")
        append(
            buildList {
                if (packageNames.isNotEmpty()) {
                    add("packages=${packageNames.joinToString()}")
                }
                add("protocol=${protocol.ifBlank { "?" }}")
                add("endpoint=${remoteHost.ifBlank { "?" }}")
                remotePort?.takeIf { port -> port in 1..65535 }?.let { port -> add("port=$port") }
                add(
                    "country=${
                        networkActivityCountryLabel(
                            countryCodeForDestination = countryCodeForDestination,
                            ipInfo = ipInfo,
                        )
                    }",
                )
                add("rx=${formatBytes(bytesRx.coerceAtLeast(0L))}")
                add("tx=${formatBytes(bytesTx.coerceAtLeast(0L))}")
                add("total=${formatBytes(totalBytes.coerceAtLeast(0L))}")
                profileId?.let { id -> add("profile=$id") }
                sessionId?.takeIf(String::isNotBlank)?.let { session -> add("session=$session") }
            }.joinToString(separator = " • "),
        )
    }

private fun NetworkActivityEvent.networkActivityCountryLabel(
    countryCodeForDestination: (String) -> String?,
    ipInfo: IpInfo?,
): String {
    val remote = remoteHost.connectionHost()
    val matchingIpInfo =
        ipInfo
            ?.takeIf { info -> remote == info.ip || remote == info.ipv4 || remote == info.ipv6 }
    val resolvedCode =
        normalizedCountryCode(countryCode)
            ?: normalizedCountryCode(countryCodeForDestination(remoteHost))
            ?: normalizedCountryCode(matchingIpInfo?.countryCode)
    val country = resolvedCode?.let { code -> "${countryDisplayName(code)} ($code)" } ?: "?"
    val city = matchingIpInfo?.city?.takeIf(String::isNotBlank)
    return listOfNotNull(country, city).joinToString(separator = " • ")
}
