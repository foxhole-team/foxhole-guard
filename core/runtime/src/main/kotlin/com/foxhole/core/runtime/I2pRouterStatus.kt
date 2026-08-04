package com.foxhole.core.runtime

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Router facts scraped from the loopback i2pd webconsole main page. The webconsole is the only
 * i2pd status surface that names the router's external address (I2PControl has no such key), and
 * we ship the exact i2pd these markers are written against — the parser is pinned to the vendored
 * HTTPServer.cpp output and unit-tested on a sample of it.
 */
data class I2pRouterStatus(
    /** First published external address, `IP:port (transport)`; null while firewalled/unknown. */
    val externalAddress: String? = null,
    /** The `Network status` line as printed (OK / Firewalled / Testing / ...). */
    val networkStatus: String? = null,
    /** netdb size (`Routers` counter). */
    val knownRouters: Int? = null,
    val transitTunnels: Int? = null,
    /** Client (in+out) tunnel count — the router's own tunnels, as opposed to transit. */
    val clientTunnels: Int? = null,
    /** i2pd's own version string. */
    val version: String? = null,
    /** Traffic as the console prints it, e.g. "12.34 MiB (5.20 KiB/s)". */
    val received: String? = null,
    val sent: String? = null,
    /** Numeric copies of the console counters for the I2P window's totals and live speed badges. */
    val rxTotalBytes: Long? = null,
    val txTotalBytes: Long? = null,
    val rxBytesPerSec: Long? = null,
    val txBytesPerSec: Long? = null,
    /** Base64 router identity — the router's address on the I2P network. */
    val routerIdent: String? = null,
)

fun parseI2pdWebConsoleStatus(html: String): I2pRouterStatus {
    val received = parseTraffic(RECEIVED_REGEX, html)
    val sent = parseTraffic(SENT_REGEX, html)
    val receivedReading = parseI2pTrafficReading(received)
    val sentReading = parseI2pTrafficReading(sent)
    return I2pRouterStatus(
        externalAddress = parseExternalAddress(html),
        networkStatus =
        NETWORK_STATUS_REGEX.find(html)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty),
        knownRouters = ROUTERS_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull(),
        transitTunnels = TRANSIT_TUNNELS_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull(),
        clientTunnels = CLIENT_TUNNELS_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull(),
        version = VERSION_REGEX.find(html)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty),
        received = received,
        sent = sent,
        rxTotalBytes = receivedReading?.totalBytes,
        txTotalBytes = sentReading?.totalBytes,
        rxBytesPerSec = receivedReading?.bytesPerSec,
        txBytesPerSec = sentReading?.bytesPerSec,
        routerIdent = ROUTER_IDENT_REGEX.find(html)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty),
    )
}

/** "12.34 MiB (5.20 KiB/s)" — the console prints the total and the 15s rate side by side. */
private fun parseTraffic(
    regex: Regex,
    html: String,
): String? =
    regex.find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()?.takeIf(String::isNotEmpty)

private fun parseI2pTrafficReading(value: String?): I2pTrafficReading? {
    val amounts =
        value?.let { traffic ->
            TRAFFIC_AMOUNT_REGEX.findAll(traffic).mapNotNull { match ->
                val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
                val multiplier =
                    when (match.groupValues[2].lowercase(Locale.ROOT)) {
                        "b" -> 1.0
                        "kb", "kib" -> 1_024.0
                        "mb", "mib" -> 1_048_576.0
                        "gb", "gib" -> 1_073_741_824.0
                        "tb", "tib" -> 1_099_511_627_776.0
                        else -> return@mapNotNull null
                    }
                (number * multiplier).coerceIn(0.0, Long.MAX_VALUE.toDouble()).roundToLong()
            }.toList()
        }.orEmpty()
    return amounts.firstOrNull()?.let { total ->
        I2pTrafficReading(
            totalBytes = total,
            bytesPerSec = amounts.getOrNull(1) ?: 0L,
        )
    }
}

private data class I2pTrafficReading(
    val totalBytes: Long,
    val bytesPerSec: Long,
)

private fun parseExternalAddress(html: String): String? {
    val table = EXTERNAL_ADDRESS_TABLE_REGEX.find(html)?.groupValues?.get(1) ?: return null
    return EXTERNAL_ADDRESS_ROW_REGEX.findAll(table)
        .mapNotNull { row ->
            val transport = row.groupValues[1].trim()
            val address = row.groupValues[2].trim()
            // An unpublished (firewalled) address prints "supported [:port]" instead of a host.
            if (address.isEmpty() || address.startsWith("supported")) null else "$address ($transport)"
        }
        .firstOrNull()
}

private val NETWORK_STATUS_REGEX = Regex("""<b>Network status:</b>\s*([^<]+)<br>""")
private val ROUTERS_REGEX = Regex("""<b>Routers:</b>\s*(\d+)""")
private val TRANSIT_TUNNELS_REGEX = Regex("""<b>Transit Tunnels:</b>\s*(\d+)""")
private val CLIENT_TUNNELS_REGEX = Regex("""<b>Client Tunnels:</b>\s*(\d+)""")
private val VERSION_REGEX = Regex("""<b>Version:</b>\s*([^<]+)<br>""")
private val ROUTER_IDENT_REGEX = Regex("""<b>Router Ident:</b>\s*([^<\s]+)""")

// The console prints the total through ShowTraffic() and appends the 15s rate in brackets, both on
// the same line: "<b>Received:</b> 12.34 MiB (5.20 KiB/s)<br>".
private val RECEIVED_REGEX = Regex("""<b>Received:</b>\s*(.*?)<br>""", RegexOption.DOT_MATCHES_ALL)
private val SENT_REGEX = Regex("""<b>Sent:</b>\s*(.*?)<br>""", RegexOption.DOT_MATCHES_ALL)
private val TRAFFIC_AMOUNT_REGEX =
    Regex("""(\d+(?:[.,]\d+)?)\s*([KMGT]?i?B)(?:/s)?""", RegexOption.IGNORE_CASE)
private val EXTERNAL_ADDRESS_TABLE_REGEX =
    Regex("""<table class="extaddr">(.*?)</table>""", RegexOption.DOT_MATCHES_ALL)
private val EXTERNAL_ADDRESS_ROW_REGEX =
    Regex("""<tr>\s*<td>([^<]+)</td>\s*<td[^>]*>([^<]+)</td>""", RegexOption.DOT_MATCHES_ALL)
