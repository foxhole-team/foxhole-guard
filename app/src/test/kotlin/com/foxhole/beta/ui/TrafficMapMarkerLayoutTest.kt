package com.foxhole.beta.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMapMarkerLayoutTest {
    @Test
    fun `marker layout separates same country route and destination nodes without moving origin`() {
        val raw = Offset(100f, 100f)
        val markers =
            listOf(
                TrafficMapMarkerProjection(
                    key = "origin",
                    countryCode = "US",
                    role = TrafficMapMarkerRole.ORIGIN,
                    rawOffset = raw,
                ),
                TrafficMapMarkerProjection(
                    key = "vpn",
                    countryCode = "US",
                    role = TrafficMapMarkerRole.VPN_ROUTE,
                    rawOffset = raw,
                ),
                TrafficMapMarkerProjection(
                    key = "tor",
                    countryCode = "US",
                    role = TrafficMapMarkerRole.TOR_EXIT,
                    rawOffset = raw,
                ),
                TrafficMapMarkerProjection(
                    key = "destination",
                    countryCode = "US",
                    role = TrafficMapMarkerRole.DESTINATION,
                    rawOffset = raw,
                ),
            )
        val placements =
            resolveTrafficMapMarkerPlacements(
                markers = markers,
                minDistancePx = 10f,
            )

        assertEquals(raw, placements.first { placement -> placement.role == TrafficMapMarkerRole.ORIGIN }.offset)
        placements
            .flatMapIndexed { index, placement ->
                placements.drop(index + 1).map { other -> placement to other }
            }
            .forEach { (left, right) ->
                assertTrue(
                    "${left.role} and ${right.role} overlap: ${left.offset} ${right.offset}",
                    left.offset.distanceTo(right.offset) >= 10f,
                )
            }
    }

    @Test
    fun `marker layout preserves input order for drawing`() {
        val markers =
            listOf(
                TrafficMapMarkerProjection(
                    key = "destination:0:DE",
                    countryCode = "DE",
                    role = TrafficMapMarkerRole.DESTINATION,
                    rawOffset = Offset(1f, 1f),
                ),
                TrafficMapMarkerProjection(
                    key = "origin",
                    countryCode = "US",
                    role = TrafficMapMarkerRole.ORIGIN,
                    rawOffset = Offset(1f, 1f),
                ),
                TrafficMapMarkerProjection(
                    key = "route:vpn",
                    countryCode = "US",
                    role = TrafficMapMarkerRole.VPN_ROUTE,
                    rawOffset = Offset(1f, 1f),
                ),
            )
        val placements =
            resolveTrafficMapMarkerPlacements(
                markers = markers,
                minDistancePx = 9f,
                viewportTopLeft = Offset.Zero,
                viewportSize = Size(width = 240f, height = 120f),
            )

        assertEquals(listOf("destination:0:DE", "origin", "route:vpn"), placements.map { placement -> placement.key })
    }
}

private fun Offset.distanceTo(other: Offset): Float {
    val dx = x - other.x
    val dy = y - other.y
    return kotlin.math.sqrt((dx * dx) + (dy * dy))
}
