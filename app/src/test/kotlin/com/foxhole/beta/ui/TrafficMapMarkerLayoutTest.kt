package com.foxhole.beta.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `marker layout annotates raw clusters while preserving resolved order`() {
        val markers =
            listOf(
                TrafficMapMarkerProjection(
                    key = "origin",
                    countryCode = "US",
                    role = TrafficMapMarkerRole.ORIGIN,
                    rawOffset = Offset(40f, 40f),
                ),
                TrafficMapMarkerProjection(
                    key = "route:vpn",
                    countryCode = "US",
                    role = TrafficMapMarkerRole.VPN_ROUTE,
                    rawOffset = Offset(40f, 40f),
                ),
                TrafficMapMarkerProjection(
                    key = "destination:0:DE",
                    countryCode = "DE",
                    role = TrafficMapMarkerRole.DESTINATION,
                    rawOffset = Offset(140f, 40f),
                ),
            )

        val placements = resolveTrafficMapMarkerPlacements(markers = markers, minDistancePx = 10f)

        assertEquals(listOf("origin", "route:vpn", "destination:0:DE"), placements.map { placement -> placement.key })
        assertEquals(2, placements[0].clusterCount)
        assertEquals(2, placements[1].clusterCount)
        assertEquals(1, placements[2].clusterCount)
    }

    @Test
    fun `route geometry suppresses direction cue for very short routes`() {
        val cue =
            trafficMapRouteDirectionCue(
                from = Offset(10f, 10f),
                to = Offset(16f, 12f),
                lane = 1,
            )

        assertNull(cue)
    }

    @Test
    fun `route geometry places direction cue inside long route bounds`() {
        val cue =
            requireNotNull(
                trafficMapRouteDirectionCue(
                    from = Offset(10f, 10f),
                    to = Offset(150f, 70f),
                    lane = 1,
                ),
            )

        assertTrue(cue.center.x in 10f..150f)
        assertTrue(cue.center.y in 10f..90f)
    }
}

private fun Offset.distanceTo(other: Offset): Float {
    val dx = x - other.x
    val dy = y - other.y
    return kotlin.math.sqrt((dx * dx) + (dy * dy))
}
