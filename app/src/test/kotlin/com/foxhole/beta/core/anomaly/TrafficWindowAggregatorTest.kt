package com.foxhole.beta.core.anomaly

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.NetworkType
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficWindowAggregatorTest {
    @Test
    fun `country traffic windows store deltas from cumulative country bytes`() {
        val aggregator = TrafficWindowAggregator(minWindowDurationMs = WINDOW_MS)
        val context =
            context(
                destinationCountries = mapOf("DE" to 1_000L, "NL" to 200L),
            )

        assertNull(aggregator.aggregate(snapshot(rx = 1_000L, tx = 100L, at = 0L), context))

        val window =
            aggregator.aggregate(
                snapshot = snapshot(rx = 2_500L, tx = 300L, at = WINDOW_MS),
                context = context(destinationCountries = mapOf("DE" to 1_600L, "NL" to 200L, "US" to 90L)),
            )

        assertEquals(mapOf("DE" to 600L, "US" to 90L), window?.destinationCountries)
    }

    @Test
    fun `country byte deltas ignore counter resets and removed countries`() {
        assertEquals(
            mapOf("FR" to 20L),
            countryByteDeltas(
                previous = mapOf("DE" to 1_000L, "FR" to 10L),
                current = mapOf("DE" to 900L, "FR" to 30L),
            ),
        )
    }

    private fun context(destinationCountries: Map<String, Long>): TrafficAggregationContext =
        TrafficAggregationContext(
            connection = ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = 7L),
            settings = Settings(),
            networkType = NetworkType.WIFI,
            destinationCountries = destinationCountries,
        )

    private fun snapshot(
        rx: Long,
        tx: Long,
        at: Long,
    ): TrafficSnapshot =
        TrafficSnapshot(
            available = true,
            rxTotalBytes = rx,
            txTotalBytes = tx,
            sampledAt = at,
        )

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}
