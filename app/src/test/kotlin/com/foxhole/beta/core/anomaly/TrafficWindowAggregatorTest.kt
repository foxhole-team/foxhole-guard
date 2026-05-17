package com.foxhole.beta.core.anomaly

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.NetworkType
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun `country traffic windows normalize countries and ignore invalid byte totals`() {
        val aggregator = TrafficWindowAggregator(minWindowDurationMs = WINDOW_MS)
        val initialCountries =
            mapOf(
                " de " to 100L,
                "DE" to 50L,
                "USA" to 1_000L,
                "FR" to -1L,
            )
        val currentCountries =
            mapOf(
                "DE" to 175L,
                " de" to 25L,
                "NL" to 15L,
                "1A" to 200L,
                "GB" to 0L,
            )

        assertNull(
            aggregator.aggregate(
                snapshot = snapshot(rx = 1_000L, tx = 100L, at = 0L),
                context = context(destinationCountries = initialCountries),
            ),
        )

        val window =
            aggregator.aggregate(
                snapshot = snapshot(rx = 2_000L, tx = 200L, at = WINDOW_MS),
                context = context(destinationCountries = currentCountries),
            )

        assertEquals(mapOf("DE" to 50L, "NL" to 15L), window?.destinationCountries)
    }

    @Test
    fun `reconnects count connected to reconnecting transitions across short samples then reset after window`() {
        val aggregator = TrafficWindowAggregator(minWindowDurationMs = WINDOW_MS)

        assertNull(
            aggregator.aggregate(
                snapshot = snapshot(rx = 100L, tx = 10L, at = 0L),
                context = context(state = ConnectionState.CONNECTED),
            ),
        )
        assertNull(
            aggregator.aggregate(
                snapshot = snapshot(rx = 200L, tx = 20L, at = 10_000L),
                context = context(state = ConnectionState.RECONNECTING),
            ),
        )
        assertNull(
            aggregator.aggregate(
                snapshot = snapshot(rx = 300L, tx = 30L, at = 20_000L),
                context = context(state = ConnectionState.CONNECTED),
            ),
        )
        assertNull(
            aggregator.aggregate(
                snapshot = snapshot(rx = 400L, tx = 40L, at = 30_000L),
                context = context(state = ConnectionState.RECONNECTING),
            ),
        )

        val reconnectWindow =
            aggregator.aggregate(
                snapshot = snapshot(rx = 1_000L, tx = 100L, at = WINDOW_MS),
                context = context(state = ConnectionState.CONNECTED),
            )
        val nextWindow =
            aggregator.aggregate(
                snapshot = snapshot(rx = 1_500L, tx = 150L, at = WINDOW_MS * 2),
                context = context(state = ConnectionState.CONNECTED),
            )

        assertEquals(2, reconnectWindow?.reconnects)
        assertEquals(0, nextWindow?.reconnects)
    }

    @Test
    fun `context reconnect count is used when it exceeds observed transitions`() {
        val aggregator = TrafficWindowAggregator(minWindowDurationMs = WINDOW_MS)

        assertNull(
            aggregator.aggregate(
                snapshot = snapshot(rx = 100L, tx = 10L, at = 0L),
                context = context(state = ConnectionState.CONNECTED),
            ),
        )

        val window =
            aggregator.aggregate(
                snapshot = snapshot(rx = 1_000L, tx = 100L, at = WINDOW_MS),
                context = context(state = ConnectionState.CONNECTED, reconnects = 3),
            )

        assertEquals(3, window?.reconnects)
    }

    @Test
    fun `traffic counter reset refreshes country baseline without emitting stale deltas`() {
        val aggregator = TrafficWindowAggregator(minWindowDurationMs = WINDOW_MS)

        assertNull(
            aggregator.aggregate(
                snapshot = snapshot(rx = 1_000L, tx = 100L, at = 0L),
                context = context(destinationCountries = mapOf("DE" to 1_000L)),
            ),
        )
        assertNull(
            aggregator.aggregate(
                snapshot = snapshot(rx = 100L, tx = 10L, at = WINDOW_MS),
                context = context(destinationCountries = mapOf("DE" to 50L, "FR" to 10L)),
            ),
        )

        val window =
            aggregator.aggregate(
                snapshot = snapshot(rx = 300L, tx = 30L, at = WINDOW_MS * 2),
                context = context(destinationCountries = mapOf("DE" to 90L, "FR" to 25L)),
            )

        assertEquals(mapOf("DE" to 40L, "FR" to 15L), window?.destinationCountries)
    }

    @Test
    fun `reset clears traffic country and reconnect baselines`() {
        val aggregator = TrafficWindowAggregator(minWindowDurationMs = WINDOW_MS)

        assertNull(
            aggregator.aggregate(
                snapshot = snapshot(rx = 100L, tx = 10L, at = 0L),
                context = context(destinationCountries = mapOf("DE" to 100L), state = ConnectionState.CONNECTED),
            ),
        )
        assertNull(
            aggregator.aggregate(
                snapshot = snapshot(rx = 200L, tx = 20L, at = 10_000L),
                context = context(destinationCountries = mapOf("DE" to 150L), state = ConnectionState.RECONNECTING),
            ),
        )

        aggregator.reset()

        assertNull(
            aggregator.aggregate(
                snapshot = snapshot(rx = 500L, tx = 50L, at = WINDOW_MS),
                context = context(destinationCountries = mapOf("DE" to 1_000L), state = ConnectionState.CONNECTED),
            ),
        )
        val window =
            aggregator.aggregate(
                snapshot = snapshot(rx = 800L, tx = 80L, at = WINDOW_MS * 2),
                context = context(destinationCountries = mapOf("DE" to 1_025L), state = ConnectionState.CONNECTED),
            )

        assertNotNull(window)
        assertEquals(mapOf("DE" to 25L), window?.destinationCountries)
        assertEquals(0, window?.reconnects)
    }

    private fun context(
        destinationCountries: Map<String, Long> = emptyMap(),
        state: ConnectionState = ConnectionState.CONNECTED,
        reconnects: Int = 0,
    ): TrafficAggregationContext =
        TrafficAggregationContext(
            connection = ConnectionSnapshot(state = state, profileId = 7L),
            settings = Settings(),
            networkType = NetworkType.WIFI,
            destinationCountries = destinationCountries,
            reconnects = reconnects,
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
