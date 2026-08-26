package com.foxhole.core.runtime

import java.util.Locale
import kotlin.math.roundToLong

data class I2pRouterStatus(

    val externalAddress: String? = null,

    val networkStatus: String? = null,

    val knownRouters: Int? = null,
    val transitTunnels: Int? = null,

    val clientTunnels: Int? = null,

    val version: String? = null,

    val received: String? = null,
    val sent: String? = null,

    val transit: String? = null,

    val rxTotalBytes: Long? = null,
    val txTotalBytes: Long? = null,
    val rxBytesPerSec: Long? = null,
    val txBytesPerSec: Long? = null,
    val transitTotalBytes: Long? = null,
    val transitBytesPerSec: Long? = null,

    val routerIdent: String? = null,
)

fun parseI2pdFoxHoleStatus(body: String): I2pRouterStatus? {
    val values =
        body.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator) to line.substring(separator + 1)
                }
            }.toMap()
    if (values["foxhole_status"] != "1") {
        return null
    }
    return I2pRouterStatus(
        networkStatus = values["network_status"]?.toIntOrNull().toI2pdNetworkStatus(),
        knownRouters = values["known_routers"]?.toIntOrNull(),
        transitTunnels = values["transit_tunnels"]?.toIntOrNull(),
        clientTunnels = values["client_tunnels"]?.toIntOrNull(),
        version = values["version"]?.takeIf(String::isNotBlank),
        rxTotalBytes = values["received_bytes"]?.toLongOrNull(),
        txTotalBytes = values["sent_bytes"]?.toLongOrNull(),
        rxBytesPerSec = values["received_bytes_per_sec"].toRoundedLongOrNull(),
        txBytesPerSec = values["sent_bytes_per_sec"].toRoundedLongOrNull(),
        transitTotalBytes = values["transit_bytes"]?.toLongOrNull(),
        transitBytesPerSec = values["transit_bytes_per_sec"].toRoundedLongOrNull(),
    )
}

private fun Int?.toI2pdNetworkStatus(): String? =
    when (this) {
        0 -> "OK"
        1 -> "Firewalled"
        2 -> "Unknown"
        3 -> "Proxy"
        4 -> "Mesh"
        5 -> "Stan"
        else -> null
    }

private fun String?.toRoundedLongOrNull(): Long? =
    this?.toDoubleOrNull()?.coerceIn(0.0, Long.MAX_VALUE.toDouble())?.roundToLong()

fun parseI2pdWebConsoleStatus(html: String): I2pRouterStatus {
    val received = parseTraffic(RECEIVED_REGEX, html)
    val sent = parseTraffic(SENT_REGEX, html)
    val transit = parseTraffic(TRANSIT_REGEX, html)
    val receivedReading = parseI2pTrafficReading(received)
    val sentReading = parseI2pTrafficReading(sent)
    val transitReading = parseI2pTrafficReading(transit)
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
        transit = transit,
        rxTotalBytes = receivedReading?.totalBytes,
        txTotalBytes = sentReading?.totalBytes,
        rxBytesPerSec = receivedReading?.bytesPerSec,
        txBytesPerSec = sentReading?.bytesPerSec,
        transitTotalBytes = transitReading?.totalBytes,
        transitBytesPerSec = transitReading?.bytesPerSec,
        routerIdent = ROUTER_IDENT_REGEX.find(html)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty),
    )
}

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

private val RECEIVED_REGEX = Regex("""<b>Received:</b>\s*(.*?)<br>""", RegexOption.DOT_MATCHES_ALL)
private val SENT_REGEX = Regex("""<b>Sent:</b>\s*(.*?)<br>""", RegexOption.DOT_MATCHES_ALL)

private val TRANSIT_REGEX = Regex("""<b>Transit:</b>\s*(.*?)<br>""", RegexOption.DOT_MATCHES_ALL)
private val TRAFFIC_AMOUNT_REGEX =
    Regex("""(\d+(?:[.,]\d+)?)\s*([KMGT]?i?B)(?:/s)?""", RegexOption.IGNORE_CASE)
private val EXTERNAL_ADDRESS_TABLE_REGEX =
    Regex("""<table class="extaddr">(.*?)</table>""", RegexOption.DOT_MATCHES_ALL)
private val EXTERNAL_ADDRESS_ROW_REGEX =
    Regex("""<tr>\s*<td>([^<]+)</td>\s*<td[^>]*>([^<]+)</td>""", RegexOption.DOT_MATCHES_ALL)
