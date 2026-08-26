package com.foxhole.guard.ui.cli.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.TrafficMapEdge
import com.foxhole.core.model.TrafficMapPoint
import com.foxhole.guard.ui.cli.CliColors
import com.foxhole.guard.ui.cli.CliLightColors
import com.foxhole.guard.ui.trafficmap.TRAFFIC_MAP_LOAD_SHAPES_TRACE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliMapPerformancePolicyTest {
    @Test
    fun `map legend uses the unified modern baseline`() {
        assertEquals(0.dp, CLI_MAP_LEGEND_LABEL_OFFSET)

        val source =
            File(
                requireNotNull(System.getProperty("user.dir")),
                "src/main/kotlin/com/foxhole/guard/ui/cli/map/CliMapScreen.kt",
            ).readText()
        assertTrue(source.contains("y = CLI_MAP_LEGEND_LABEL_OFFSET"))
        assertFalse(source.contains("VisualStyle"))
    }

    @Test
    fun `land cache accounts argb bytes without integer overflow`() {
        assertEquals(4L, cliMapBitmapByteCount(1, 1))
        assertEquals(33_177_600L, cliMapBitmapByteCount(3_840, 2_160))
        assertEquals(4L, cliMapBitmapByteCount(0, 0))
        assertEquals(Long.MAX_VALUE, cliMapBitmapByteCount(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `land cache evicts least recently used entry by byte budget`() {
        val cache = CliMapByteLru<String, Long>(maxBytes = 10L, sizeOf = { it })
        cache["first"] = 4L
        cache["second"] = 4L
        assertEquals(4L, cache["first"])

        cache["third"] = 4L

        assertTrue(cache.contains("first"))
        assertFalse(cache.contains("second"))
        assertTrue(cache.contains("third"))
        assertEquals(8L, cache.byteCount)
    }

    @Test
    fun `land cache rejects oversized entries without evicting bounded entries`() {
        val cache = CliMapByteLru<String, Long>(maxBytes = 10L, sizeOf = { it })
        cache["small"] = 4L
        cache["oversized"] = 16L

        assertEquals(1, cache.size)
        assertTrue(cache.contains("small"))
        assertFalse(cache.contains("oversized"))
        assertEquals(4L, cache.byteCount)

        val emptyCache = CliMapByteLru<String, Long>(maxBytes = 10L, sizeOf = { it })
        emptyCache["oversized"] = 16L
        assertEquals(0, emptyCache.size)
        assertEquals(0L, emptyCache.byteCount)
    }

    @Test
    fun `map animation stays eligible while visible geometry is present`() {
        val idlePoint = mapPoint(bytes = 0L, connections = 0)

        assertTrue(
            cliMapHasMotionContent(
                vpnRoute = idlePoint,
                torExit = null,
                dnsServer = null,
                destinations = listOf(idlePoint),
                edges = listOf(mapEdge(bytes = 0L)),
            ),
        )
        assertTrue(
            cliMapHasMotionContent(
                vpnRoute = null,
                torExit = null,
                dnsServer = null,
                destinations = emptyList(),
                edges = emptyList(),
                originAvailable = true,
            ),
        )
        assertFalse(
            cliMapHasMotionContent(
                vpnRoute = null,
                torExit = null,
                dnsServer = null,
                destinations = emptyList(),
                edges = emptyList(),
            ),
        )
    }

    @Test
    fun `map uses a lifecycle bounded low frequency ticker`() {
        val source = File(
            requireNotNull(System.getProperty("user.dir")),
            "src/main/kotlin/com/foxhole/guard/ui/cli/map/CliPixelMap.kt",
        ).readText()

        assertTrue(source.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"))
        assertTrue(source.contains("MotionDurationScale"))
        assertTrue(source.contains("MAP_MOTION_FRAME_MS = 100L"))
        assertTrue(source.contains("MAP_MOTION_FRAME_COUNT = 24"))
        assertFalse(source.contains("Animatable"))
        assertFalse(source.contains("MAP_ACTIVITY_IDLE_TIMEOUT_MS"))
        assertFalse(source.contains("cliMapActivityFingerprint"))
    }

    @Test
    fun `production map trace names stay stable for perfetto and benchmarks`() {
        assertEquals("TrafficMap/loadShapes", TRAFFIC_MAP_LOAD_SHAPES_TRACE)
        assertEquals("TrafficMap/renderLandBitmap", TRAFFIC_MAP_RENDER_LAND_BITMAP_TRACE)
        assertEquals("TrafficMap/buildRoutes", TRAFFIC_MAP_BUILD_ROUTES_TRACE)
        assertEquals("TrafficMap/draw", TRAFFIC_MAP_DRAW_TRACE)
    }

    @Test
    fun `shared warm semantic map palette stays stable`() {
        val dark = CliColors()
        assertEquals(Color(0xFF1F1A12), dark.map.water)
        assertEquals(Color(0xFF3F3731), dark.map.land)
        assertEquals(Color(0xFFA85210), dark.map.coast)
        assertEquals(dark.data, dark.map.route)
        assertEquals(Color(0xFF423625), dark.map.grid)
        assertEquals(dark.fg, dark.map.marker)

        assertEquals(Color(0xFFE7D8BE), CliLightColors.map.water)
        assertEquals(Color(0xFFBBA991), CliLightColors.map.land)
        assertEquals(Color(0xFFA94508), CliLightColors.map.coast)
        assertEquals(CliLightColors.data, CliLightColors.map.route)
        assertEquals(Color(0xFFB7A17D), CliLightColors.map.grid)
        assertEquals(CliLightColors.fg, CliLightColors.map.marker)
    }

    @Test
    fun `map dns rendering uses info without remapping the data route token`() {
        val source =
            File(
                requireNotNull(System.getProperty("user.dir")),
                "src/main/kotlin/com/foxhole/guard/ui/cli/map/CliPixelMap.kt",
            ).readText()
        val dnsRoute = source
            .substringAfter("geometry.dnsLine?.let")
            .substringBefore("geometry.destinations.forEach")

        assertTrue(source.contains("standardFill = colors.map.land"))
        assertTrue(source.contains("val landBoundaryColor = colors.map.coast"))
        assertTrue(source.contains("drawRect(color = colors.map.water)"))
        assertTrue(dnsRoute.contains("color = colors.info.copy(alpha = 0.75f)"))
        assertTrue(dnsRoute.contains("drawPath(path = head, color = colors.info)"))
        assertFalse(dnsRoute.contains("colors.map.route"))
        assertTrue(
            source.contains(
                "geometry.dnsServer?.let { center -> drawMarker(center, colors.info, geometry, pulse) }",
            ),
        )
        assertTrue(source.contains("colors.map.grid"))
        assertTrue(source.contains("colors.map.marker"))
        assertFalse(source.contains("lerp(colors.panel, colors.info"))
    }

    private fun mapPoint(
        bytes: Long,
        connections: Int = 1,
    ) =
        TrafficMapPoint(
            countryCode = "DE",
            label = "Germany",
            lat = 51.0,
            lon = 10.0,
            bytes = bytes,
            connections = connections,
        )

    private fun mapEdge(bytes: Long) =
        TrafficMapEdge(
            fromLat = 40.0,
            fromLon = -74.0,
            toLat = 51.0,
            toLon = 10.0,
            bytes = bytes,
        )
}
