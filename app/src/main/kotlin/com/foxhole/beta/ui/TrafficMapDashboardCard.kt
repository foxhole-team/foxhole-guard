package com.foxhole.beta.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.traffic.TrafficMapCountryGeoJsonParser
import com.foxhole.beta.core.traffic.TrafficMapCountryShape
import com.foxhole.beta.core.traffic.TrafficMapGeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
internal fun TrafficMapDashboardCard(
    state: TrafficMapUiState,
    modifier: Modifier = Modifier,
) {
    val countries by rememberTrafficMapCountries()
    FoxholeCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_traffic_map_card"),
    ) {
        HomeCardHeader(
            icon = Icons.Outlined.Map,
            title = stringResource(R.string.traffic_map_title),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRAFFIC_MAP_CARD_CONTENT_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            TrafficWorldMap(
                state = state,
                countries = countries.orEmpty(),
                modifier = Modifier
                    .weight(TRAFFIC_MAP_WEIGHT)
                    .fillMaxHeight()
                    .testTag("home_traffic_world_map"),
            )
            TrafficMapLegend(
                state = state,
                modifier = Modifier
                    .weight(TRAFFIC_MAP_LEGEND_WEIGHT)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun TrafficWorldMap(
    state: TrafficMapUiState,
    countries: List<TrafficMapCountryShape>,
    modifier: Modifier = Modifier,
) {
    val latestState = rememberUpdatedState(state)
    val colorScheme = MaterialTheme.colorScheme
    val systemTone = foxholeSystemAwareAccentColor(fallback = colorScheme.primary)
    val mapBackgroundColor = colorScheme.surfaceVariant.copy(alpha = 0.46f)
    val baseCountryColor = colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val highlightedCountryColor = systemTone.copy(alpha = 0.36f)
    val borderColor = colorScheme.outlineVariant.copy(alpha = 0.34f)
    val lineColor = systemTone.copy(alpha = 0.46f)
    val originColor = systemTone
    val destinationColor = FoxholePositiveAccent
    val phoneScreenColor = colorScheme.surface.copy(alpha = 0.88f)
    val textColor = colorScheme.onSurface.toArgb()
    val countryShapes = remember(countries) { countries }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .drawWithCache {
                val viewport = trafficMapViewport(size)
                val countryPaths = countryShapes.mapNotNull { shape -> shape.toProjectedPath(viewport) }
                val borderStroke = Stroke(width = 0.55.dp.toPx())
                val maxLineStroke = 2.1.dp.toPx()
                val minLineStroke = 0.65.dp.toPx()
                val destinationRadius = 3.3.dp.toPx()
                val glowRadius = 8.dp.toPx()
                val cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
                val phoneWidth = 8.dp.toPx()
                val phoneHeight = 12.dp.toPx()
                val phoneCorner = CornerRadius(2.2.dp.toPx(), 2.2.dp.toPx())
                val phoneScreenInset = 1.4.dp.toPx()
                val phoneHomeRadius = 0.65.dp.toPx()
                val destinationLabelPaint =
                    trafficMapLabelPaint(
                        color = textColor,
                        textSize = 8.sp.toPx(),
                        bold = false,
                    )

                onDrawBehind {
                    drawRoundRect(
                        color = mapBackgroundColor,
                        cornerRadius = cornerRadius,
                    )
                    val mapState = latestState.value
                    countryPaths.forEach { country ->
                        val fillColor =
                            if (country.countryCode in mapState.highlightedCountries) {
                                highlightedCountryColor
                            } else {
                                baseCountryColor
                            }
                        drawPath(
                            path = country.path,
                            color = fillColor,
                        )
                    }
                    countryPaths.forEach { country ->
                        drawPath(
                            path = country.path,
                            color = borderColor,
                            style = borderStroke,
                        )
                    }

                    val maxBytes = mapState.edges.maxOfOrNull { edge -> edge.bytes }?.coerceAtLeast(1L) ?: 1L
                    val edgeCount = min(mapState.edges.size, MAX_TRAFFIC_MAP_DRAW_EDGES)
                    for (index in 0 until edgeCount) {
                        val edge = mapState.edges[index]
                        val from = project(edge.fromLat, edge.fromLon, viewport)
                        val to = project(edge.toLat, edge.toLon, viewport)
                        val weight = edge.bytes.toFloat() / maxBytes.toFloat()
                        drawLine(
                            color = lineColor.copy(alpha = 0.22f + (0.28f * weight)),
                            start = from,
                            end = to,
                            strokeWidth = minLineStroke + ((maxLineStroke - minLineStroke) * weight),
                            cap = StrokeCap.Round,
                        )
                    }

                    val destinationCount = min(mapState.destinations.size, MAX_TRAFFIC_MAP_DRAW_DESTINATIONS)
                    for (index in 0 until destinationCount) {
                        val point = mapState.destinations[index]
                        val offset = project(point.lat, point.lon, viewport)
                        drawCircle(
                            color = destinationColor.copy(alpha = 0.22f),
                            radius = glowRadius,
                            center = offset,
                        )
                        drawCircle(
                            color = destinationColor,
                            radius = destinationRadius,
                            center = offset,
                        )
                        if (index < MAX_TRAFFIC_MAP_LABELS) {
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawText(
                                    point.countryCode,
                                    offset.x,
                                    offset.y - 6.dp.toPx(),
                                    destinationLabelPaint,
                                )
                            }
                        }
                    }

                    val origin = project(mapState.originLat, mapState.originLon, viewport)
                    drawCircle(
                        color = originColor.copy(alpha = 0.24f),
                        radius = glowRadius,
                        center = origin,
                    )
                    drawPhoneMarker(
                        center = origin,
                        bodyColor = originColor,
                        screenColor = phoneScreenColor,
                        width = phoneWidth,
                        height = phoneHeight,
                        cornerRadius = phoneCorner,
                        screenInset = phoneScreenInset,
                        homeRadius = phoneHomeRadius,
                    )
                }
            }
            .fillMaxSize(),
    )
}

@Composable
private fun TrafficMapLegend(
    state: TrafficMapUiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val destinations = remember(state.destinations) { state.destinations.take(MAX_TRAFFIC_MAP_LEGEND_DESTINATIONS) }
    val originLabel = remember(state.originCity, state.originCountryName, state.originCountryCode) { state.originLocationLabel() }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = originLabel,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp,
            ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (destinations.isEmpty()) {
            Text(
                text =
                    stringResource(
                        if (state.isAvailable) {
                            R.string.traffic_map_waiting_connections
                        } else {
                            R.string.traffic_map_live_requires_firewall
                        },
                    ),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                destinations.forEach { point ->
                    TrafficMapLegendDestinationRow(context = context, point = point)
                }
            }
        }
    }
}

private fun TrafficMapUiState.originLocationLabel(): String =
    listOfNotNull(
        originCity,
        originCountryName
            ?.let { countryName -> originCountryCode?.let { code -> "$countryName · $code" } ?: countryName }
            ?: originCountryCode,
    )
        .joinToString(separator = "\n")
        .ifBlank { "IP" }

@Composable
private fun TrafficMapLegendDestinationRow(
    context: Context,
    point: TrafficMapPoint,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .padding(0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = FoxholePositiveAccent,
            ) {}
        }
        Text(
            text = "${countryEmoji(point.countryCode)} ${point.countryCode} · " +
                "${point.connections} · ${formatBytes(context, point.bytes)}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                lineHeight = 11.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun rememberTrafficMapCountries(): State<List<TrafficMapCountryShape>?> {
    val appContext = LocalContext.current.applicationContext
    return produceState<List<TrafficMapCountryShape>?>(initialValue = null, appContext) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { loadTrafficMapCountries(appContext) }.getOrDefault(emptyList())
            }
    }
}

private fun loadTrafficMapCountries(context: Context): List<TrafficMapCountryShape> {
    // Later: if 50m is too heavy on low-end devices, add a 110m asset and switch this path.
    val raw =
        context.assets.open(TRAFFIC_MAP_COUNTRIES_ASSET).bufferedReader().use { reader ->
            reader.readText()
        }
    return TrafficMapCountryGeoJsonParser().parse(raw)
        .filterNot { country -> country.countryCode == TRAFFIC_MAP_ANTARCTICA_COUNTRY_CODE }
}

private fun TrafficMapCountryShape.toProjectedPath(viewport: TrafficMapViewport): ProjectedTrafficMapCountry? {
    val path =
        Path().apply {
            fillType = PathFillType.EvenOdd
        }
    var hasRing = false
    rings.forEach { ring ->
        if (ring.size >= MIN_TRAFFIC_MAP_RING_POINTS) {
            ring.addToPath(path, viewport)
            hasRing = true
        }
    }
    return ProjectedTrafficMapCountry(countryCode = countryCode, path = path).takeIf { hasRing }
}

private fun List<TrafficMapGeoPoint>.addToPath(
    path: Path,
    viewport: TrafficMapViewport,
) {
    val first = firstOrNull() ?: return
    val firstOffset = project(first.lat, first.lon, viewport)
    path.moveTo(firstOffset.x, firstOffset.y)
    for (index in 1 until size) {
        val point = this[index]
        val offset = project(point.lat, point.lon, viewport)
        path.lineTo(offset.x, offset.y)
    }
    path.close()
}

private fun project(
    lat: Double,
    lon: Double,
    viewport: TrafficMapViewport,
): Offset {
    val projected = project(lat = lat, lon = lon, size = viewport.size)
    return Offset(
        x = viewport.topLeft.x + projected.x,
        y = viewport.topLeft.y + projected.y,
    )
}

private fun project(
    lat: Double,
    lon: Double,
    size: Size,
): Offset {
    val x = ((lon + 180.0) / 360.0 * size.width).toFloat()
    val y = ((TRAFFIC_MAP_MAX_LAT - lat) / TRAFFIC_MAP_LAT_RANGE * size.height).toFloat()
    return Offset(x, y)
}

private fun DrawScope.drawPhoneMarker(
    center: Offset,
    bodyColor: androidx.compose.ui.graphics.Color,
    screenColor: androidx.compose.ui.graphics.Color,
    width: Float,
    height: Float,
    cornerRadius: CornerRadius,
    screenInset: Float,
    homeRadius: Float,
) {
    val topLeft = Offset(x = center.x - width / 2f, y = center.y - height / 2f)
    drawRoundRect(
        color = bodyColor,
        topLeft = topLeft,
        size = Size(width = width, height = height),
        cornerRadius = cornerRadius,
    )
    drawRoundRect(
        color = screenColor,
        topLeft = Offset(x = topLeft.x + screenInset, y = topLeft.y + screenInset),
        size = Size(width = width - (screenInset * 2f), height = height - (screenInset * 2.7f)),
        cornerRadius = CornerRadius(screenInset, screenInset),
    )
    drawCircle(
        color = bodyColor,
        radius = homeRadius,
        center = Offset(x = center.x, y = topLeft.y + height - screenInset * 0.72f),
    )
}

private fun trafficMapViewport(size: Size): TrafficMapViewport {
    val widthForHeight = size.height * TRAFFIC_MAP_WORLD_ASPECT_RATIO
    return if (widthForHeight <= size.width) {
        val mapSize = Size(width = widthForHeight, height = size.height)
        TrafficMapViewport(
            topLeft = Offset(x = (size.width - widthForHeight) / 2f, y = 0f),
            size = mapSize,
        )
    } else {
        val heightForWidth = size.width / TRAFFIC_MAP_WORLD_ASPECT_RATIO
        TrafficMapViewport(
            topLeft = Offset(x = 0f, y = (size.height - heightForWidth) / 2f),
            size = Size(width = size.width, height = heightForWidth),
        )
    }
}

private fun trafficMapLabelPaint(
    color: Int,
    textSize: Float,
    bold: Boolean,
): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = textSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

private data class TrafficMapViewport(
    val topLeft: Offset,
    val size: Size,
)

private data class ProjectedTrafficMapCountry(
    val countryCode: String,
    val path: Path,
)

private val TRAFFIC_MAP_CARD_CONTENT_HEIGHT = 156.dp
private const val TRAFFIC_MAP_WEIGHT = 0.70f
private const val TRAFFIC_MAP_LEGEND_WEIGHT = 0.30f
private const val TRAFFIC_MAP_WORLD_ASPECT_RATIO = 2f
private const val TRAFFIC_MAP_MIN_LAT = -55.0
private const val TRAFFIC_MAP_MAX_LAT = 85.0
private const val TRAFFIC_MAP_LAT_RANGE = TRAFFIC_MAP_MAX_LAT - TRAFFIC_MAP_MIN_LAT
private const val MAX_TRAFFIC_MAP_DRAW_EDGES = 60
private const val MAX_TRAFFIC_MAP_DRAW_DESTINATIONS = 60
private const val MAX_TRAFFIC_MAP_LEGEND_DESTINATIONS = 5
private const val MAX_TRAFFIC_MAP_LABELS = 6
private const val MIN_TRAFFIC_MAP_RING_POINTS = 3
private const val TRAFFIC_MAP_COUNTRIES_ASSET = "maps/ne_50m_admin_0_countries.geojson"
private const val TRAFFIC_MAP_ANTARCTICA_COUNTRY_CODE = "AQ"
