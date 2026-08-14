package com.foxhole.guard.ui

import android.content.Context
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.runtime.TorGeoIpCountryResolver
import com.foxhole.guard.R
import com.foxhole.guard.statistics.countryDisplayName
import com.foxhole.guard.statistics.normalizedCountryCode

internal const val NETWORK_ACTIVITY_TAG = "activity"

// Formatting of network-activity events into journal entries (endpoints, countries).
// Split from SettingsDiagnosticsScreen.kt.
// The connections journal is always raw — the pseudonymization layer (stable aliases per
// package/host/profile) was dead in every UI call site and is gone; hiding data in the user's own
// on-device journal protected nothing. Exported diagnostics files keep their own sanitizer.

internal fun networkActivityDiagnosticEntries(
    events: List<NetworkActivityEvent>,
    context: Context,
    countryResolver: TorGeoIpCountryResolver,
    ipInfo: IpInfo?,
): List<DiagnosticEntry> {
    // Resolved once for the whole list: one label serves every event, and the unknown-city string
    // used to be hard-coded in English in the middle of a localised journal.
    val unknownLabel = context.getString(R.string.cli_common_unknown)
    return events.map { event ->
        DiagnosticEntry(
            timestamp = event.timestampMs,
            tag = NETWORK_ACTIVITY_TAG,
            message = event.toNetworkActivityDiagnosticMessage(
                formatBytes = { bytes -> formatBytes(context, bytes) },
                unknownLabel = unknownLabel,
                countryCodeForDestination = countryResolver::countryCodeForDestination,
                ipInfo = ipInfo,
            ),
        )
    }
}

internal fun fallbackNetworkDiagnosticEntries(entries: List<DiagnosticEntry>): List<DiagnosticEntry> =
    entries.filter { it.tag == NETWORK_ACTIVITY_TAG }

internal fun NetworkActivityEvent.toNetworkActivityDiagnosticMessage(
    formatBytes: (Long) -> String,
    unknownLabel: String,
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
                            unknownLabel = unknownLabel,
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
    unknownLabel: String,
): String {
    val remote = remoteHost.connectionHost()
    val matchingIpInfo =
        ipInfo
            ?.takeIf { info -> remote == info.ip || remote == info.ipv4 || remote == info.ipv6 }
    val resolvedCode =
        normalizedCountryCode(countryCode)
            ?: normalizedCountryCode(countryCodeForDestination(remoteHost))
            ?: normalizedCountryCode(matchingIpInfo?.countryCode)
    val city = matchingIpInfo?.city?.takeIf(String::isNotBlank) ?: unknownLabel
    return resolvedCode
        ?.let { code -> "${countryDisplayName(code)} ($code) • $city" }
        ?: "$unknownLabel • $city"
}
