package com.foxhole.guard.ui.cli.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.TrafficMapEdge
import com.foxhole.core.model.TrafficMapEdgeRole
import com.foxhole.core.model.TrafficMapPoint
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.trafficmap.TrafficMapAssetState
import com.foxhole.guard.ui.trafficmap.TrafficMapAssets
import kotlin.math.abs
import kotlin.math.floor

/** Cell grid of the pixel world map: 192x96 (both multiples of the bitmap-cache bucket). */
private const val GRID_W = 192
private const val GRID_H = 96

/**
 * The 16-bit traffic map: the existing GeoJSON land layer is rasterized once at 192x96 via
 * the dedicated CLI land cache and drawn integer-upscaled with FilterQuality.None -
 * chunky pixels by construction. Route markers land on the same grid as scalexscale cells;
 * connection markers blink in a hard step (no easing - it must read as a terminal cursor).
 */
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
) {
    val colors = LocalCliColors.current
    val panelAppearance = LocalCliPanelAppearance.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { TrafficMapAssets.prewarm(context) }
    val assetState by TrafficMapAssets.state.collectAsStateWithLifecycle()
    val landFillColor = cliMapLandFillColor(
        appearance = panelAppearance,
        standardFill = lerp(colors.panel, colors.info, 0.10f),
    )
    val landBoundaryColor = lerp(colors.panel, colors.info, 0.22f)

    val landBitmap by produceState<ImageBitmap?>(
        null,
        assetState,
        landFillColor,
        landBoundaryColor,
    ) {
        val ready = assetState as? TrafficMapAssetState.Ready ?: return@produceState
        value = CliMapLandCache.bitmap(
            shapes = ready.shapes,
            canvasSize = IntSize(GRID_W, GRID_H),
            // Standard keeps the blue-graphite land. Dark appearance deliberately skips country
            // fills while retaining the brighter geographic outline and all semantic route ink.
            fillColor = landFillColor,
            boundaryColor = landBoundaryColor,
            pointStride = 2,
        )
    }

    val blink = rememberInfiniteTransition(label = "cliMapBlink")
    val blinkValue = blink.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "cliMapBlinkValue",
    )
    // blinkValue changes every frame, but the body needs only two discrete derivatives; reading it
    // directly recomposed the map 60 times a second for nothing.
    val blinkOn by remember { derivedStateOf { blinkValue.value < 0.5f } }
    // Discrete marching-dash phase: four steps per cycle, no smooth slide — dots step cell to cell
    // like a cursor.
    val antPhase by remember { derivedStateOf { (blinkValue.value * ANT_STEPS_PER_CYCLE).toInt() % 2 } }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(GRID_W.toFloat() / GRID_H.toFloat()),
    ) {
        val bitmap = landBitmap ?: return@Canvas
        val scale = floor(size.width / bitmap.width).coerceAtLeast(1f)
        val dstW = (bitmap.width * scale).toInt()
        val dstH = (bitmap.height * scale).toInt()
        val offsetX = ((size.width - dstW) / 2f).toInt()
        val offsetY = ((size.height - dstH) / 2f).toInt()
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(offsetX, offsetY),
            dstSize = IntSize(dstW, dstH),
            filterQuality = FilterQuality.None,
        )

        val gridSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())

        // Dotted Bresenham lines between route nodes - every second cell, dimmed,
        // marching along the line in hard 300ms steps.
        drawRouteEdges(
            edges = edges,
            colors = colors,
            phase = antPhase,
            gridSize = gridSize,
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
        )

        destinations.forEach { destination ->
            val cell = mapCell(destination.lat, destination.lon, gridSize)
            val color = if (blinkOn) colors.info else colors.info.copy(alpha = 0.35f)
            drawMapCell(cell, color, scale, offsetX, offsetY)
        }

        // The model's default origin is a placeholder (Paris) until geo data resolves -
        // drawing it would present it as the user's location.
        if (originAvailable) {
            val cell = mapCell(origin.first, origin.second, gridSize)
            drawMarkerHalo(cell, colors.info, scale, offsetX, offsetY)
            drawMarker(cell, colors.fg, scale, offsetX, offsetY)
        }
        vpnRoute?.let {
            drawMarker(mapCell(it.lat, it.lon, gridSize), colors.vpn, scale, offsetX, offsetY)
        }
        torExit?.let {
            drawMarker(mapCell(it.lat, it.lon, gridSize), colors.tor, scale, offsetX, offsetY)
        }
        dnsServer?.let {
            drawMarker(mapCell(it.lat, it.lon, gridSize), colors.info, scale, offsetX, offsetY)
        }
    }
}

internal fun cliMapLandFillColor(
    appearance: PanelAppearance,
    standardFill: Color,
): Color? = standardFill.takeIf { appearance == PanelAppearance.STANDARD }

/** Marching-ants steps per blink cycle. */
private const val ANT_STEPS_PER_CYCLE = 4

/**
 * Projects coordinates into a grid cell. Top-level rather than local to Canvas, where it and
 * [drawMapCell] were recreated as closures on every animation frame.
 */
private fun mapCell(lat: Double, lon: Double, gridSize: Size): IntOffset {
    val projected = cliProjectMap(lat, lon, gridSize)
    return IntOffset(
        projected.x.toInt().coerceIn(0, gridSize.width.toInt() - 1),
        projected.y.toInt().coerceIn(0, gridSize.height.toInt() - 1),
    )
}

private fun DrawScope.drawMapCell(
    cell: IntOffset,
    color: Color,
    scale: Float,
    offsetX: Int,
    offsetY: Int,
) {
    drawRect(
        color = color,
        topLeft = Offset(offsetX + cell.x * scale, offsetY + cell.y * scale),
        size = Size(scale, scale),
    )
}

private fun DrawScope.drawRouteEdges(
    edges: List<TrafficMapEdge>,
    colors: com.foxhole.guard.ui.cli.CliColors,
    phase: Int,
    gridSize: Size,
    scale: Float,
    offsetX: Int,
    offsetY: Int,
) {
    edges.forEach { edge ->
        val from = mapCell(edge.fromLat, edge.fromLon, gridSize)
        val to = mapCell(edge.toLat, edge.toLon, gridSize)
        val color = edgeColor(edge.role, colors).copy(alpha = 0.75f)
        bresenham(from, to) { cell, index ->
            if ((index + phase) % 2 == 0) drawMapCell(cell, color, scale, offsetX, offsetY)
        }
    }
}

private fun edgeColor(role: TrafficMapEdgeRole, colors: com.foxhole.guard.ui.cli.CliColors): Color =
    when (role) {
        TrafficMapEdgeRole.VPN_ROUTE, TrafficMapEdgeRole.VPN_DESTINATION -> colors.vpn
        TrafficMapEdgeRole.TOR_ROUTE, TrafficMapEdgeRole.TOR_DESTINATION -> colors.tor
        TrafficMapEdgeRole.DIRECT -> colors.dim
    }

// Top-level: a list literal inside the draw function allocated the list and four Pairs per marker
// per frame.
private val MARKER_HALO_OFFSETS = listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)
private val MARKER_ARM_OFFSETS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

/** Brand-blue halo around the "you" cross: four diagonal cells. */
private fun DrawScope.drawMarkerHalo(
    cell: IntOffset,
    color: Color,
    scale: Float,
    offsetX: Int,
    offsetY: Int,
) {
    val haloColor = color.copy(alpha = 0.45f)
    for ((dx, dy) in MARKER_HALO_OFFSETS) {
        drawMapCell(IntOffset(cell.x + dx, cell.y + dy), haloColor, scale, offsetX, offsetY)
    }
}

/** 3x3 cross marker: the center cell plus 4 neighbours - reads as a map pin at 1x. */
private fun DrawScope.drawMarker(
    cell: IntOffset,
    color: Color,
    scale: Float,
    offsetX: Int,
    offsetY: Int,
) {
    drawMapCell(cell, color, scale, offsetX, offsetY)
    val armColor = color.copy(alpha = 0.6f)
    for ((dx, dy) in MARKER_ARM_OFFSETS) {
        drawMapCell(IntOffset(cell.x + dx, cell.y + dy), armColor, scale, offsetX, offsetY)
    }
}

private inline fun bresenham(
    from: IntOffset,
    to: IntOffset,
    draw: (IntOffset, Int) -> Unit,
) {
    var x = from.x
    var y = from.y
    val dx = abs(to.x - from.x)
    val dy = -abs(to.y - from.y)
    val sx = if (from.x < to.x) 1 else -1
    val sy = if (from.y < to.y) 1 else -1
    var err = dx + dy
    var index = 0
    var done = false
    while (!done && index <= GRID_W * 2) {
        draw(IntOffset(x, y), index)
        done = x == to.x && y == to.y
        if (!done) {
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
            index++
        }
    }
}
