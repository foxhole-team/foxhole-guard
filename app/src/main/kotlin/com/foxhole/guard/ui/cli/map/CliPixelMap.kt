package com.foxhole.guard.ui.cli.map

import android.os.Trace
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.TrafficMapEdge
import com.foxhole.core.model.TrafficMapEdgeRole
import com.foxhole.core.model.TrafficMapPoint
import com.foxhole.guard.ui.cli.CliRadius
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.trafficmap.TrafficMapAssetState
import com.foxhole.guard.ui.trafficmap.TrafficMapAssets
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.hypot
import kotlin.math.sin

private const val GRID_W = 192
private const val GRID_H = 96

@Composable
internal fun CliPixelMap(
    origin: Pair<Double, Double>,
    originAvailable: Boolean,
    vpnRoute: TrafficMapPoint?,
    torExit: TrafficMapPoint?,
    dnsServer: TrafficMapPoint?,
    destinations: List<TrafficMapPoint>,
    edges: List<TrafficMapEdge>,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = 0.dp,
    prewarmAssets: Boolean = true,
) {
    val colors = LocalCliColors.current
    val panelAppearance = LocalCliPanelAppearance.current
    val context = LocalContext.current
    val assetState by TrafficMapAssets.state.collectAsStateWithLifecycle()
    if (prewarmAssets) {
        CliPixelMapAssetPrewarmEffect(
            assetState = assetState,
            prewarm = { TrafficMapAssets.prewarm(context) },
        )
    }
    val landFillColor = cliMapLandFillColor(
        appearance = panelAppearance,
        standardFill = colors.map.land,
    )
    val landBoundaryColor = colors.map.coast

    var canvasPx by remember { mutableStateOf(IntSize.Zero) }
    val landBitmap by produceState<ImageBitmap?>(
        null,
        assetState,
        landFillColor,
        landBoundaryColor,
        canvasPx,
    ) {
        val ready = assetState as? TrafficMapAssetState.Ready ?: return@produceState
        if (canvasPx.width == 0 || canvasPx.height == 0) return@produceState
        value = CliMapLandCache.bitmap(
            shapes = ready.shapes,
            canvasSize = canvasPx,
            fillColor = landFillColor,
            boundaryColor = landBoundaryColor,
            pointStride = 1,
            antiAlias = true,
        )
    }

    val geometryInput = remember(origin, originAvailable, vpnRoute, torExit, dnsServer, destinations, edges) {
        cliMapGeometryInput(
            origin = origin,
            originAvailable = originAvailable,
            vpnRoute = vpnRoute,
            torExit = torExit,
            dnsServer = dnsServer,
            destinations = destinations,
            edges = edges,
        )
    }
    val geometry = remember(canvasPx, geometryInput) {
        buildCliMapGeometryOrNull(
            canvasSize = canvasPx,
            input = geometryInput,
        )
    }

    val wave = rememberCliMapWave(geometryInput.hasMotionContent)
    Canvas(
        modifier = modifier
            .padding(horizontal = horizontalInset)
            .fillMaxWidth()
            .aspectRatio(GRID_W.toFloat() / GRID_H.toFloat())
            .clip(RoundedCornerShape(CliRadius.panel))
            .onSizeChanged { canvasPx = it },
    ) {
        val bitmap = landBitmap ?: return@Canvas
        val currentGeometry = geometry ?: return@Canvas
        traceTrafficMapSection(TRAFFIC_MAP_DRAW_TRACE) {
            drawMap(
                bitmap = bitmap,
                colors = colors,
                geometry = currentGeometry,
                wave = wave.value,
            )
        }
    }
}

@Composable
private fun rememberCliMapWave(active: Boolean): State<Float> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(initialValue = 0f, active, lifecycle) {
        if (!active) {
            value = 0f
            return@produceState
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var frame = 0
            while (currentCoroutineContext().isActive) {
                if ((currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f) == 0f) {
                    value = 0f
                    delay(MAP_MOTION_FRAME_MS)
                    continue
                }
                value = frame.toFloat() / MAP_MOTION_FRAME_COUNT
                frame = (frame + 1) % MAP_MOTION_FRAME_COUNT
                delay(MAP_MOTION_FRAME_MS)
            }
        }
    }
}

@Composable
internal fun CliPixelMapAssetPrewarmEffect(
    assetState: TrafficMapAssetState,
    prewarm: () -> Unit,
) {
    val currentPrewarm by rememberUpdatedState(prewarm)
    LaunchedEffect(assetState) {
        if (assetState is TrafficMapAssetState.Idle) currentPrewarm()
    }
}

private data class CliMapCoordinate(
    val lat: Double,
    val lon: Double,
)

private data class CliMapEdgeGeometryInput(
    val from: CliMapCoordinate,
    val to: CliMapCoordinate,
    val role: TrafficMapEdgeRole,
)

private data class CliMapGeometryInput(
    val origin: CliMapCoordinate?,
    val vpnRoute: CliMapCoordinate?,
    val torExit: CliMapCoordinate?,
    val dnsServer: CliMapCoordinate?,
    val dnsFrom: CliMapCoordinate?,
    val destinations: List<CliMapCoordinate>,
    val edges: List<CliMapEdgeGeometryInput>,
    val hasMotionContent: Boolean,
)

private data class CliMapLine(
    val from: Offset,
    val to: Offset,
    val role: TrafficMapEdgeRole,
)

private data class CliMapGeometry(
    val unit: Float,
    val edgeLines: List<CliMapLine>,
    val dnsLine: Pair<Offset, Offset>?,
    val dnsArrowHead: Path?,
    val destinations: List<Offset>,
    val origin: Offset?,
    val vpnRoute: Offset?,
    val torExit: Offset?,
    val dnsServer: Offset?,
    val dashIntervals: FloatArray,
    val staticDashEffect: PathEffect,
    val originStroke: Stroke,
    val markerStroke: Stroke,
)

@Suppress("LongParameterList")
private fun cliMapGeometryInput(
    origin: Pair<Double, Double>,
    originAvailable: Boolean,
    vpnRoute: TrafficMapPoint?,
    torExit: TrafficMapPoint?,
    dnsServer: TrafficMapPoint?,
    destinations: List<TrafficMapPoint>,
    edges: List<TrafficMapEdge>,
): CliMapGeometryInput {
    val originCoordinate = origin.takeIf { originAvailable }?.let { CliMapCoordinate(it.first, it.second) }
    val vpnCoordinate = vpnRoute?.let { CliMapCoordinate(it.lat, it.lon) }
    val torCoordinate = torExit?.let { CliMapCoordinate(it.lat, it.lon) }
    return CliMapGeometryInput(
        origin = originCoordinate,
        vpnRoute = vpnCoordinate,
        torExit = torCoordinate,
        dnsServer = dnsServer?.let { CliMapCoordinate(it.lat, it.lon) },
        dnsFrom = vpnCoordinate ?: torCoordinate ?: originCoordinate,
        destinations = destinations.map { point -> CliMapCoordinate(point.lat, point.lon) },
        edges = edges.map { edge ->
            CliMapEdgeGeometryInput(
                from = CliMapCoordinate(edge.fromLat, edge.fromLon),
                to = CliMapCoordinate(edge.toLat, edge.toLon),
                role = edge.role,
            )
        },
        hasMotionContent = cliMapHasMotionContent(
            vpnRoute = vpnRoute,
            torExit = torExit,
            dnsServer = dnsServer,
            destinations = destinations,
            edges = edges,
            originAvailable = originCoordinate != null,
        ),
    )
}

internal fun cliMapHasMotionContent(
    vpnRoute: TrafficMapPoint?,
    torExit: TrafficMapPoint?,
    dnsServer: TrafficMapPoint?,
    destinations: List<TrafficMapPoint>,
    edges: List<TrafficMapEdge>,
    originAvailable: Boolean = false,
): Boolean =
    originAvailable ||
        vpnRoute != null ||
        torExit != null ||
        dnsServer != null ||
        destinations.isNotEmpty() ||
        edges.isNotEmpty()

private fun buildCliMapGeometry(
    canvasSize: IntSize,
    input: CliMapGeometryInput,
): CliMapGeometry = buildMapGeometry(canvasSize, input)

private fun buildCliMapGeometryOrNull(
    canvasSize: IntSize,
    input: CliMapGeometryInput,
): CliMapGeometry? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
    return traceTrafficMapSection(TRAFFIC_MAP_BUILD_ROUTES_TRACE) {
        buildCliMapGeometry(
            canvasSize = canvasSize,
            input = input,
        )
    }
}

private fun buildMapGeometry(
    canvasSize: IntSize,
    input: CliMapGeometryInput,
): CliMapGeometry {
    val size = Size(canvasSize.width.toFloat(), canvasSize.height.toFloat())
    val unit = size.width / GRID_W
    val dnsLine =
        input.dnsFrom?.let { from ->
            input.dnsServer?.let { to ->
                cliProjectMap(from.lat, from.lon, size) to cliProjectMap(to.lat, to.lon, size)
            }
        }
    val dashIntervals = floatArrayOf(unit * 1.4f, unit * 1.4f)
    return CliMapGeometry(
        unit = unit,
        edgeLines = input.edges.map { edge ->
            CliMapLine(
                from = cliProjectMap(edge.from.lat, edge.from.lon, size),
                to = cliProjectMap(edge.to.lat, edge.to.lon, size),
                role = edge.role,
            )
        },
        dnsLine = dnsLine,
        dnsArrowHead = dnsLine?.let { (from, to) -> buildArrowHead(from, to, unit) },
        destinations = input.destinations.map { point -> cliProjectMap(point.lat, point.lon, size) },
        origin = input.origin?.let { point -> cliProjectMap(point.lat, point.lon, size) },
        vpnRoute = input.vpnRoute?.let { point -> cliProjectMap(point.lat, point.lon, size) },
        torExit = input.torExit?.let { point -> cliProjectMap(point.lat, point.lon, size) },
        dnsServer = input.dnsServer?.let { point -> cliProjectMap(point.lat, point.lon, size) },
        dashIntervals = dashIntervals,
        staticDashEffect = PathEffect.dashPathEffect(dashIntervals, 0f),
        originStroke = Stroke(width = unit * 0.5f),
        markerStroke = Stroke(width = unit * 0.45f),
    )
}

internal fun cliMapLandFillColor(
    appearance: PanelAppearance,
    standardFill: Color,
): Color? = standardFill.takeIf { appearance != PanelAppearance.DARK }

private fun DrawScope.drawMap(
    bitmap: ImageBitmap,
    colors: com.foxhole.guard.ui.cli.CliColors,
    geometry: CliMapGeometry,
    wave: Float,
) {
    drawRect(color = colors.map.water)
    drawImage(
        image = bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        filterQuality = FilterQuality.High,
    )
    val pulse = (sin(wave * TWO_PI) + 1f) / 2f
    val dashEffect =
        if (wave == 0f) {
            geometry.staticDashEffect
        } else {
            PathEffect.dashPathEffect(
                intervals = geometry.dashIntervals,
                phase = wave * geometry.dashIntervals[0] * 2f,
            )
        }
    geometry.edgeLines.forEach { edge ->
        drawLine(
            color = edgeColor(edge.role, colors).copy(alpha = 0.75f),
            start = edge.from,
            end = edge.to,
            strokeWidth = geometry.unit * 0.5f,
            cap = StrokeCap.Round,
            pathEffect = dashEffect,
        )
    }
    geometry.dnsLine?.let { (from, to) ->
        drawLine(
            color = colors.info.copy(alpha = 0.75f),
            start = from,
            end = to,
            strokeWidth = geometry.unit * 0.5f,
            cap = StrokeCap.Round,
            pathEffect = dashEffect,
        )
        geometry.dnsArrowHead?.let { head -> drawPath(path = head, color = colors.info) }
    }
    geometry.destinations.forEach { center ->
        drawCircle(
            color = colors.info.copy(alpha = 0.35f + 0.65f * pulse),
            radius = geometry.unit * (0.5f + 0.15f * pulse),
            center = center,
        )
    }
    geometry.origin?.let { center ->
        drawCircle(
            color = colors.info.copy(alpha = 0.30f + 0.30f * pulse),
            radius = geometry.unit * (1.35f + 0.3f * pulse),
            center = center,
            style = geometry.originStroke,
        )
        drawCircle(color = colors.map.marker, radius = geometry.unit * 0.8f, center = center)
    }
    geometry.vpnRoute?.let { center -> drawMarker(center, colors.vpn, geometry, pulse) }
    geometry.torExit?.let { center -> drawMarker(center, colors.tor, geometry, pulse) }
    geometry.dnsServer?.let { center -> drawMarker(center, colors.info, geometry, pulse) }
}

private fun DrawScope.drawMarker(
    center: Offset,
    color: Color,
    geometry: CliMapGeometry,
    pulse: Float,
) {
    drawCircle(
        color = color.copy(alpha = 0.25f + 0.25f * pulse),
        radius = geometry.unit * (1.2f + 0.25f * pulse),
        center = center,
        style = geometry.markerStroke,
    )
    drawCircle(color = color, radius = geometry.unit * 0.8f, center = center)
}

private fun buildArrowHead(from: Offset, to: Offset, unit: Float): Path? {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy)
    if (len < unit) return null
    val ux = dx / len
    val uy = dy / len
    val headLen = unit * 2.2f
    val headHalf = unit * 1.1f
    val baseX = to.x - ux * headLen
    val baseY = to.y - uy * headLen
    return Path().apply {
        moveTo(to.x, to.y)
        lineTo(baseX - uy * headHalf, baseY + ux * headHalf)
        lineTo(baseX + uy * headHalf, baseY - ux * headHalf)
        close()
    }
}

private const val TWO_PI = (Math.PI * 2).toFloat()

private fun edgeColor(role: TrafficMapEdgeRole, colors: com.foxhole.guard.ui.cli.CliColors): Color =
    when (role) {
        TrafficMapEdgeRole.VPN_ROUTE, TrafficMapEdgeRole.VPN_DESTINATION -> colors.vpn
        TrafficMapEdgeRole.TOR_ROUTE, TrafficMapEdgeRole.TOR_DESTINATION -> colors.tor
        TrafficMapEdgeRole.DIRECT -> colors.map.grid
    }

private inline fun <T> traceTrafficMapSection(sectionName: String, block: () -> T): T {
    Trace.beginSection(sectionName)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

private const val MAP_MOTION_FRAME_MS = 100L
private const val MAP_MOTION_FRAME_COUNT = 24
internal const val TRAFFIC_MAP_BUILD_ROUTES_TRACE = "TrafficMap/buildRoutes"
internal const val TRAFFIC_MAP_DRAW_TRACE = "TrafficMap/draw"
