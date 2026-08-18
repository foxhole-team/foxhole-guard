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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.TrafficMapEdge
import com.foxhole.core.model.TrafficMapEdgeRole
import com.foxhole.core.model.TrafficMapPoint
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.trafficmap.TrafficMapAssetState
import com.foxhole.guard.ui.trafficmap.TrafficMapAssets
import kotlin.math.abs
import kotlin.math.floor
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
) {
    val colors = LocalCliColors.current
    val panelAppearance = LocalCliPanelAppearance.current
    val plain = LocalCliVisualStyle.current == VisualStyle.PLAIN
    val context = LocalContext.current
    LaunchedEffect(Unit) { TrafficMapAssets.prewarm(context) }
    val assetState by TrafficMapAssets.state.collectAsStateWithLifecycle()
    val landFillColor = cliMapLandFillColor(
        appearance = panelAppearance,
        standardFill = lerp(colors.panel, colors.info, 0.10f),
    )
    val landBoundaryColor = lerp(colors.panel, colors.info, if (plain) 0.45f else 0.22f)

    var canvasPx by remember { mutableStateOf(IntSize.Zero) }
    val landBitmap by produceState<ImageBitmap?>(
        null,
        assetState,
        landFillColor,
        landBoundaryColor,
        plain,
        canvasPx,
    ) {
        val ready = assetState as? TrafficMapAssetState.Ready ?: return@produceState
        if (plain && (canvasPx.width == 0 || canvasPx.height == 0)) return@produceState
        value = CliMapLandCache.bitmap(
            shapes = ready.shapes,
            canvasSize = if (plain) canvasPx else IntSize(GRID_W, GRID_H),
            fillColor = landFillColor,
            boundaryColor = landBoundaryColor,
            pointStride = if (plain) 1 else 2,
            antiAlias = plain,
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
    val blinkOn by remember { derivedStateOf { blinkValue.value < 0.5f } }
    val antPhase by remember { derivedStateOf { (blinkValue.value * ANT_STEPS_PER_CYCLE).toInt() % 2 } }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(GRID_W.toFloat() / GRID_H.toFloat())
            .onSizeChanged { canvasPx = it },
    ) {
        val bitmap = landBitmap ?: return@Canvas
        if (plain) {
            drawPlainMap(
                bitmap = bitmap,
                colors = colors,
                origin = origin,
                originAvailable = originAvailable,
                vpnRoute = vpnRoute,
                torExit = torExit,
                dnsServer = dnsServer,
                destinations = destinations,
                edges = edges,
                wave = blinkValue.value,
            )
        } else {
            drawPixelMap(
                bitmap = bitmap,
                colors = colors,
                origin = origin,
                originAvailable = originAvailable,
                vpnRoute = vpnRoute,
                torExit = torExit,
                dnsServer = dnsServer,
                destinations = destinations,
                edges = edges,
                blinkOn = blinkOn,
                phase = antPhase,
            )
        }
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawPixelMap(
    bitmap: ImageBitmap,
    colors: com.foxhole.guard.ui.cli.CliColors,
    origin: Pair<Double, Double>,
    originAvailable: Boolean,
    vpnRoute: TrafficMapPoint?,
    torExit: TrafficMapPoint?,
    dnsServer: TrafficMapPoint?,
    destinations: List<TrafficMapPoint>,
    edges: List<TrafficMapEdge>,
    blinkOn: Boolean,
    phase: Int,
) {
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

    drawRouteEdges(
        edges = edges,
        colors = colors,
        phase = phase,
        gridSize = gridSize,
        scale = scale,
        offsetX = offsetX,
        offsetY = offsetY,
    )

    val dnsFrom = dnsLaneStart(origin, originAvailable, vpnRoute, torExit)
    if (dnsServer != null && dnsFrom != null) {
        val from = mapCell(dnsFrom.first, dnsFrom.second, gridSize)
        val to = mapCell(dnsServer.lat, dnsServer.lon, gridSize)
        val color = colors.note.copy(alpha = 0.75f)
        bresenham(from, to) { cell, index ->
            if ((index + phase) % 2 == 0) drawMapCell(cell, color, scale, offsetX, offsetY)
        }
    }

    destinations.forEach { destination ->
        val cell = mapCell(destination.lat, destination.lon, gridSize)
        val color = if (blinkOn) colors.info else colors.info.copy(alpha = 0.35f)
        drawMapCell(cell, color, scale, offsetX, offsetY)
    }

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
        drawMarker(mapCell(it.lat, it.lon, gridSize), colors.note, scale, offsetX, offsetY)
    }
}

internal fun cliMapLandFillColor(
    appearance: PanelAppearance,
    standardFill: Color,
): Color? = standardFill.takeIf { appearance != PanelAppearance.DARK }

/**
 * Same geometry and palette as the pixel map: smooth land, dashed route lines, round markers.
 * [wave] is the continuous 0..1 cycle: dashes drift with it and every dot breathes on the
 * same sine, so nothing blinks discretely.
 */
@Suppress("LongParameterList")
private fun DrawScope.drawPlainMap(
    bitmap: ImageBitmap,
    colors: com.foxhole.guard.ui.cli.CliColors,
    origin: Pair<Double, Double>,
    originAvailable: Boolean,
    vpnRoute: TrafficMapPoint?,
    torExit: TrafficMapPoint?,
    dnsServer: TrafficMapPoint?,
    destinations: List<TrafficMapPoint>,
    edges: List<TrafficMapEdge>,
    wave: Float,
) {
    drawImage(
        image = bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        filterQuality = FilterQuality.High,
    )
    val unit = size.width / GRID_W
    val gridSize = Size(size.width, size.height)
    val dash = unit * 1.4f
    val pulse = (sin(wave * TWO_PI) + 1f) / 2f
    edges.forEach { edge ->
        val from = cliProjectMap(edge.fromLat, edge.fromLon, gridSize)
        val to = cliProjectMap(edge.toLat, edge.toLon, gridSize)
        drawLine(
            color = edgeColor(edge.role, colors).copy(alpha = 0.75f),
            start = from,
            end = to,
            strokeWidth = unit * 0.5f,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(dash, dash),
                wave * dash * 2f,
            ),
        )
    }
    val dnsFrom = dnsLaneStart(origin, originAvailable, vpnRoute, torExit)
    if (dnsServer != null && dnsFrom != null) {
        val from = cliProjectMap(dnsFrom.first, dnsFrom.second, gridSize)
        val to = cliProjectMap(dnsServer.lat, dnsServer.lon, gridSize)
        drawLine(
            color = colors.note.copy(alpha = 0.75f),
            start = from,
            end = to,
            strokeWidth = unit * 0.5f,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(dash, dash),
                wave * dash * 2f,
            ),
        )
        drawPlainArrowHead(from = from, to = to, color = colors.note, unit = unit)
    }
    destinations.forEach { destination ->
        val center = cliProjectMap(destination.lat, destination.lon, gridSize)
        drawCircle(
            color = colors.info.copy(alpha = 0.35f + 0.65f * pulse),
            radius = unit * (0.5f + 0.15f * pulse),
            center = center,
        )
    }
    if (originAvailable) {
        val center = cliProjectMap(origin.first, origin.second, gridSize)
        drawCircle(
            color = colors.info.copy(alpha = 0.30f + 0.30f * pulse),
            radius = unit * (1.35f + 0.3f * pulse),
            center = center,
            style = Stroke(width = unit * 0.5f),
        )
        drawCircle(color = colors.fg, radius = unit * 0.8f, center = center)
    }
    vpnRoute?.let { drawPlainMarker(cliProjectMap(it.lat, it.lon, gridSize), colors.vpn, unit, pulse) }
    torExit?.let { drawPlainMarker(cliProjectMap(it.lat, it.lon, gridSize), colors.tor, unit, pulse) }
    dnsServer?.let { drawPlainMarker(cliProjectMap(it.lat, it.lon, gridSize), colors.note, unit, pulse) }
}

private fun dnsLaneStart(
    origin: Pair<Double, Double>,
    originAvailable: Boolean,
    vpnRoute: TrafficMapPoint?,
    torExit: TrafficMapPoint?,
): Pair<Double, Double>? = when {
    vpnRoute != null -> vpnRoute.lat to vpnRoute.lon
    torExit != null -> torExit.lat to torExit.lon
    originAvailable -> origin
    else -> null
}

private fun DrawScope.drawPlainMarker(center: Offset, color: Color, unit: Float, pulse: Float) {
    drawCircle(
        color = color.copy(alpha = 0.25f + 0.25f * pulse),
        radius = unit * (1.2f + 0.25f * pulse),
        center = center,
        style = Stroke(width = unit * 0.45f),
    )
    drawCircle(color = color, radius = unit * 0.8f, center = center)
}

private fun DrawScope.drawPlainArrowHead(from: Offset, to: Offset, color: Color, unit: Float) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy)
    if (len < unit) return
    val ux = dx / len
    val uy = dy / len
    val headLen = unit * 2.2f
    val headHalf = unit * 1.1f
    val baseX = to.x - ux * headLen
    val baseY = to.y - uy * headLen
    val head = Path().apply {
        moveTo(to.x, to.y)
        lineTo(baseX - uy * headHalf, baseY + ux * headHalf)
        lineTo(baseX + uy * headHalf, baseY - ux * headHalf)
        close()
    }
    drawPath(path = head, color = color)
}

private const val TWO_PI = (Math.PI * 2).toFloat()

private const val ANT_STEPS_PER_CYCLE = 4

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

private val MARKER_HALO_OFFSETS = listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)
private val MARKER_ARM_OFFSETS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

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
