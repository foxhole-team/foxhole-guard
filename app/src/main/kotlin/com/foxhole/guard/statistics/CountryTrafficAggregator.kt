package com.foxhole.guard.statistics

import com.foxhole.core.model.TrafficMapPoint
import com.foxhole.core.model.TrafficWindow
import java.util.Locale

const val MAX_COUNTRY_SERIES = 5

const val OTHER_COUNTRY_CODE = "OTHER"

fun countryTrafficRows(
    trafficWindows: List<TrafficWindow>,
    liveDestinations: List<TrafficMapPoint>,
): List<CountryTrafficUiRow> {
    val bytesByCountry = linkedMapOf<String, Long>()
    val sessionsByCountry = linkedMapOf<String, Int>()
    accumulateWindowCountryTraffic(trafficWindows, bytesByCountry, sessionsByCountry)
    val sourceQuality =
        if (bytesByCountry.isEmpty() && liveDestinations.isNotEmpty()) {
            ChartDataQuality.PARTIAL
        } else {
            ChartDataQuality.REAL
        }
    if (bytesByCountry.isEmpty()) {
        accumulateLiveCountryTraffic(liveDestinations, bytesByCountry, sessionsByCountry)
    }
    val labelsByCountry =
        liveDestinations.associate { point ->
            point.countryCode.uppercase(Locale.US) to point.label
        }
    return buildSortedCountryRows(bytesByCountry, sessionsByCountry, labelsByCountry, sourceQuality)
}

private fun accumulateWindowCountryTraffic(
    trafficWindows: List<TrafficWindow>,
    bytesByCountry: MutableMap<String, Long>,
    sessionsByCountry: MutableMap<String, Int>,
) {
    trafficWindows.forEach { window ->
        window.destinationCountries.forEach { (countryCode, bytes) ->
            val normalized = normalizedCountryCode(countryCode) ?: return@forEach
            bytesByCountry[normalized] = (bytesByCountry[normalized] ?: 0L) + bytes.coerceAtLeast(0L)
            if (bytes > 0L) {
                sessionsByCountry[normalized] = (sessionsByCountry[normalized] ?: 0) + 1
            }
        }
    }
}

private fun accumulateLiveCountryTraffic(
    liveDestinations: List<TrafficMapPoint>,
    bytesByCountry: MutableMap<String, Long>,
    sessionsByCountry: MutableMap<String, Int>,
) {
    liveDestinations.forEach { point ->
        val normalized = normalizedCountryCode(point.countryCode) ?: return@forEach
        bytesByCountry[normalized] = (bytesByCountry[normalized] ?: 0L) + point.bytes.coerceAtLeast(0L)
        sessionsByCountry[normalized] = (sessionsByCountry[normalized] ?: 0) + point.connections.coerceAtLeast(0)
    }
}

private fun buildSortedCountryRows(
    bytesByCountry: Map<String, Long>,
    sessionsByCountry: Map<String, Int>,
    labelsByCountry: Map<String, String>,
    sourceQuality: ChartDataQuality,
): List<CountryTrafficUiRow> =
    bytesByCountry
        .map { (countryCode, bytes) ->
            CountryTrafficUiRow(
                countryCode = countryCode,
                label = labelsByCountry[countryCode] ?: countryDisplayName(countryCode),
                bytes = bytes,
                sessions = sessionsByCountry[countryCode]?.coerceAtLeast(1) ?: 1,
                quality = sourceQuality,
            )
        }
        .filter { row -> row.bytes > 0L }
        .sortedWith(
            compareByDescending<CountryTrafficUiRow> { row -> row.bytes }
                .thenByDescending { row -> row.sessions }
                .thenBy { row -> row.countryCode },
        )

fun collapseCountryOverflow(
    rows: List<CountryTrafficUiRow>,
    maxCountries: Int = MAX_COUNTRY_SERIES,
): List<CountryTrafficUiRow> {
    if (rows.size <= maxCountries) {
        return rows
    }
    val visible = rows.take(maxCountries)
    val overflow = rows.drop(maxCountries)
    return visible +
        CountryTrafficUiRow(
            countryCode = OTHER_COUNTRY_CODE,
            label = "Other",
            bytes = overflow.sumOf(CountryTrafficUiRow::bytes),
            sessions = overflow.sumOf(CountryTrafficUiRow::sessions).coerceAtLeast(1),
            quality = ChartDataQuality.PARTIAL,
        )
}

fun normalizedCountryCode(countryCode: String?): String? =
    countryCode
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { code -> code.length == 2 && code.all { character -> character in 'A'..'Z' } }

fun countryDisplayName(countryCode: String): String =
    Locale.Builder()
        .setRegion(countryCode)
        .build()
        .displayCountry
        .takeIf(String::isNotBlank)
        ?: countryCode
