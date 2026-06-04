package com.foxhole.beta.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.R
import com.foxhole.beta.core.model.TrafficMapEdge
import com.foxhole.beta.core.model.TrafficMapEdgeRole
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapPointRole
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.traffic.TrafficMapCountryShape
import com.foxhole.beta.core.traffic.TrafficMapCountryShapeAssetParser
import com.foxhole.beta.ui.theme.LocalFoxholeDarkTheme
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath

private object TrafficMapRenderDispatcher {
    val dispatcher: CoroutineDispatcher =
        Executors
            .newSingleThreadExecutor(TrafficMapRenderThreadFactory)
            .asCoroutineDispatcher()
}

private object TrafficMapRenderThreadFactory : ThreadFactory {
    private val sequence = AtomicInteger(0)

    override fun newThread(runnable: Runnable): Thread =
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            },
            "FoxholeTrafficMap-${sequence.incrementAndGet()}",
        ).apply {
            isDaemon = true
        }
}

@Composable
internal fun TrafficMapDashboardCard(
    stateFlow: StateFlow<TrafficMapUiState>,
    modifier: Modifier = Modifier,
    contentReady: Boolean = true,
    legendLoading: Boolean = false,
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    TrafficMapDashboardCard(
        state = state,
        modifier = modifier,
        contentReady = contentReady,
        legendLoading = legendLoading,
    )
}

@Composable
internal fun TrafficMapDashboardCard(
    state: TrafficMapUiState,
    modifier: Modifier = Modifier,
    contentReady: Boolean = true,
    legendLoading: Boolean = false,
) {
    val powerState = rememberTrafficMapPowerState()
    var forceMapEnabled by rememberSaveable { mutableStateOf(false) }
    val mapDisabledForPower = powerState.mapDisabled && !forceMapEnabled
    val heavyContentReady = rememberTrafficMapHeavyContentReady(contentReady && !mapDisabledForPower)
    val countryShapes =
        if (heavyContentReady) {
            rememberTrafficMapCountryShapes()
        } else {
            remember { emptyList() }
        }
    val countryShapesLoading = heavyContentReady && countryShapes.isEmpty()
    FoxholeCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_traffic_map_card"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRAFFIC_MAP_CARD_TOTAL_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(TRAFFIC_MAP_WEIGHT)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HomeCardHeader(
                    icon = Icons.Outlined.Map,
                    title = stringResource(R.string.traffic_map_title),
                )
                if (mapDisabledForPower) {
                    TrafficMapPowerSaveBlock(
                        onEnable = { forceMapEnabled = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else if (!heavyContentReady || countryShapesLoading) {
                    TrafficMapCanvasLoadingBlock(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("home_traffic_world_map_loading"),
                    )
                } else {
                    TrafficMapCanvas(
                        state = state,
                        countryShapes = countryShapes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("home_traffic_world_map"),
                    )
                }
            }
            if (!mapDisabledForPower) {
                if (legendLoading || !heavyContentReady || countryShapesLoading) {
                    TrafficMapLegendLoadingBlock(
                        modifier = Modifier
                            .weight(TRAFFIC_MAP_LEGEND_WEIGHT)
                            .fillMaxHeight(),
                    )
                } else {
                    TrafficMapLegend(
                        state = state,
                        modifier = Modifier
                            .weight(TRAFFIC_MAP_LEGEND_WEIGHT)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberTrafficMapHeavyContentReady(enabled: Boolean): Boolean {
    var ready by remember(enabled) {
        mutableStateOf(enabled && TrafficMapCountryShapeCache.current().isNotEmpty())
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            ready = false
            return@LaunchedEffect
        }
        if (TrafficMapCountryShapeCache.current().isNotEmpty()) {
            ready = true
            return@LaunchedEffect
        }
        ready = false
        if (TRAFFIC_MAP_HEAVY_CONTENT_SETTLE_DELAY_MS > 0L) {
            delay(TRAFFIC_MAP_HEAVY_CONTENT_SETTLE_DELAY_MS)
        }
        ready = true
    }
    return enabled && ready
}

@Composable
private fun TrafficMapCanvasLoadingBlock(modifier: Modifier = Modifier) {
    val shimmerProgress = rememberFoxholeSkeletonProgress()
    Column(
        modifier = modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FoxholeSkeletonBlock(
            modifier = Modifier.fillMaxWidth(0.92f).height(86.dp),
            shimmerProgress = shimmerProgress,
        )
        Row(
            modifier = Modifier.fillMaxWidth(0.72f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FoxholeSkeletonBlock(
                modifier = Modifier.width(42.dp).height(8.dp),
                shimmerProgress = shimmerProgress,
            )
            FoxholeSkeletonBlock(
                modifier = Modifier.width(72.dp).height(8.dp),
                shimmerProgress = shimmerProgress,
            )
        }
    }
}

@Composable
private fun TrafficMapLegendLoadingBlock(modifier: Modifier = Modifier) {
    val shimmerProgress = rememberFoxholeSkeletonProgress()
    Column(
        modifier = modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FoxholeSkeletonBlock(
                modifier = Modifier.size(16.dp),
                shimmerProgress = shimmerProgress,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                FoxholeSkeletonBlock(
                    modifier = Modifier.width(56.dp).height(9.dp),
                    shimmerProgress = shimmerProgress,
                )
                FoxholeSkeletonBlock(
                    modifier = Modifier.width(42.dp).height(8.dp),
                    shimmerProgress = shimmerProgress,
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            FoxholeSkeletonBlock(
                modifier = Modifier.fillMaxWidth(0.92f).height(8.dp),
                shimmerProgress = shimmerProgress,
            )
            FoxholeSkeletonBlock(
                modifier = Modifier.fillMaxWidth(0.74f).height(8.dp),
                shimmerProgress = shimmerProgress,
            )
            FoxholeSkeletonBlock(
                modifier = Modifier.fillMaxWidth(0.52f).height(8.dp),
                shimmerProgress = shimmerProgress,
            )
        }
    }
}

@Composable
private fun TrafficMapPowerSaveBlock(
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = FoxholePositiveAccent
    Column(
        modifier = modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.traffic_map_power_save_disabled),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedButton(
            onClick = onEnable,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = tone),
        ) {
            Text(
                text = stringResource(R.string.enable_label),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun TrafficMapCanvas(
    state: TrafficMapUiState,
    countryShapes: List<TrafficMapCountryShape>,
    modifier: Modifier = Modifier,
) {
    val colors = trafficMapColors()
    val mapCountryShapes = countryShapes
    val drawableDestinations = remember(state.destinations) { state.destinations.toDrawableTrafficMapDestinations() }
    val drawableVpnRoute = remember(state.vpnRoute) { state.vpnRoute?.toDrawableTrafficMapDestination() }
    val drawableTorExit = remember(state.torExit) { state.torExit?.toDrawableTrafficMapDestination() }
    val drawableEdges = remember(state.edges) { state.edges.toDrawableTrafficMapEdges() }
    val originLat = state.originLat
    val originLon = state.originLon
    val originCountryCode = state.originCountryCode
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val countryBitmap =
        rememberTrafficMapLandLayerBitmap(
            shapes = mapCountryShapes,
            canvasSize = canvasSize,
            color = colors.countryFill,
        )

    Box(
        modifier = modifier
            .onSizeChanged { size -> canvasSize = size }
            .drawWithCache {
                val viewport = trafficMapViewport(size)
                val maxLineStroke = TRAFFIC_MAP_ROUTE_MAX_STROKE_DP.dp.toPx()
                val minLineStroke = TRAFFIC_MAP_ROUTE_MIN_STROKE_DP.dp.toPx()
                val routeHaloStrokeExtra = TRAFFIC_MAP_ROUTE_HALO_STROKE_EXTRA_DP.dp.toPx()
                val defaultDestinationRadius = 3.15.dp.toPx()
                val europeDestinationRadius = 2.35.dp.toPx()
                val routeNodeRadius = 4.6.dp.toPx()
                val torNodeRadius = 4.25.dp.toPx()
                val phoneWidth = 8.5.dp.toPx()
                val phoneHeight = 12.8.dp.toPx()
                val phoneCorner = CornerRadius(2.4.dp.toPx(), 2.4.dp.toPx())
                val phoneScreenInset = 1.4.dp.toPx()
                val phoneHomeRadius = 0.65.dp.toPx()
                val origin = project(originLat, originLon, viewport)
                val maxBytes =
                    (
                        drawableEdges.maxOfOrNull { edge -> edge.bytes }
                            ?: drawableDestinations.maxOfOrNull { destination -> destination.bytes }
                            ?: 1L
                    ).coerceAtLeast(1L)
                val routeLanes = trafficRouteLanes(origin, drawableEdges, viewport)
                val routeDrawModels =
                    if (drawableEdges.isNotEmpty()) {
                        drawableEdges
                            .take(MAX_TRAFFIC_MAP_DRAW_EDGES)
                            .map { edge ->
                                val from = project(edge.fromLat, edge.fromLon, viewport)
                                val to = project(edge.toLat, edge.toLon, viewport)
                                val weight = sqrt(edge.bytes.toDouble() / maxBytes.toDouble()).toFloat()
                                TrafficMapRouteDrawModel(
                                    path =
                                        curvedTrafficRoutePath(
                                            from = from,
                                            to = to,
                                            lane = routeLanes[trafficMapEdgeLaneKey(edge)] ?: 1,
                                        ),
                                    strokeWidth = minLineStroke + ((maxLineStroke - minLineStroke) * weight),
                                    alpha =
                                        TRAFFIC_MAP_ROUTE_MIN_ALPHA +
                                            (TRAFFIC_MAP_ROUTE_ALPHA_RANGE * weight),
                                    role = edge.role,
                                )
                            }
                    } else {
                        emptyList()
                    }
                val destinationOffsets =
                    drawableDestinations
                        .take(MAX_TRAFFIC_MAP_DRAW_DESTINATIONS)
                        .map { point -> point to project(point.lat, point.lon, viewport) }
                val vpnRouteOffset = drawableVpnRoute?.let { point -> point to project(point.lat, point.lon, viewport) }
                val torExitOffset = drawableTorExit?.let { point -> point to project(point.lat, point.lon, viewport) }
                val originMarker = origin.takeIf { originCountryCode != null }

                onDrawBehind {
                    countryBitmap?.let { bitmap ->
                        drawImage(
                            image = bitmap,
                            dstOffset =
                                IntOffset(
                                    x = viewport.topLeft.x.roundToInt(),
                                    y = viewport.topLeft.y.roundToInt(),
                                ),
                            dstSize =
                                IntSize(
                                    width = viewport.size.width.roundToInt().coerceAtLeast(1),
                                    height = viewport.size.height.roundToInt().coerceAtLeast(1),
                                ),
                        )
                    }

                    routeDrawModels.forEach { route ->
                        drawPath(
                            path = route.path,
                            color =
                                colors.routeHalo.copy(
                                    alpha = (route.alpha * TRAFFIC_MAP_ROUTE_HALO_ALPHA_MULTIPLIER).coerceAtMost(1f),
                                ),
                            style =
                                Stroke(
                                    width = route.strokeWidth + routeHaloStrokeExtra,
                                    cap = StrokeCap.Round,
                                ),
                        )
                        drawPath(
                            path = route.path,
                            color =
                                when (route.role) {
                                    TrafficMapEdgeRole.DIRECT -> colors.origin
                                    TrafficMapEdgeRole.VPN_ROUTE,
                                    TrafficMapEdgeRole.TOR_ROUTE,
                                    -> colors.routeLine
                                }.copy(alpha = route.alpha),
                            style =
                                Stroke(
                                    width = route.strokeWidth,
                                    cap = StrokeCap.Round,
                                ),
                        )
                    }

                    destinationOffsets.forEach { (point, offset) ->
                        drawCircle(
                            color = colors.destination,
                            radius =
                                if (point.countryCode in TRAFFIC_MAP_EUROPE_COUNTRY_CODES) {
                                    europeDestinationRadius
                                } else {
                                    defaultDestinationRadius
                                },
                            center = offset,
                        )
                    }

                    vpnRouteOffset?.let { (_, offset) ->
                        drawCircle(
                            color = colors.vpnRoute,
                            radius = routeNodeRadius,
                            center = offset,
                        )
                    }

                    torExitOffset?.let { (_, offset) ->
                        drawCircle(
                            color = colors.torExit,
                            radius = torNodeRadius,
                            center = offset,
                        )
                    }

                    originMarker?.let { origin ->
                        drawPhoneMarker(
                            center = origin,
                            bodyColor = colors.origin,
                            screenColor = colors.phoneScreen,
                            width = phoneWidth,
                            height = phoneHeight,
                            cornerRadius = phoneCorner,
                            screenInset = phoneScreenInset,
                            homeRadius = phoneHomeRadius,
                        )
                    }
                }
            }
            .fillMaxSize(),
    )
}

@Composable
private fun rememberTrafficMapLandLayerBitmap(
    shapes: List<TrafficMapCountryShape>,
    canvasSize: IntSize,
    color: Color,
): ImageBitmap? {
    val cachedBitmap =
        remember(shapes, canvasSize, color) {
            TrafficMapLandLayerCache.currentBitmap(
                shapes = shapes,
                canvasSize = canvasSize,
                color = color,
            ) ?: TrafficMapLandLayerCache.latestBitmap()
        }
    val bitmap by produceState(
        initialValue = cachedBitmap,
        key1 = shapes,
        key2 = canvasSize,
        key3 = color,
    ) {
        if (cachedBitmap != null) {
            value = cachedBitmap
        }
        if (shapes.isEmpty() || canvasSize.width <= 0 || canvasSize.height <= 0) {
            return@produceState
        }
        value =
            TrafficMapLandLayerCache.bitmap(
                shapes = shapes,
                canvasSize = canvasSize,
                color = color,
            )
    }
    return bitmap
}

@Composable
private fun rememberTrafficMapCountryShapes(): List<TrafficMapCountryShape> {
    val appContext = LocalContext.current.applicationContext
    val shapes by produceState(
        initialValue = TrafficMapCountryShapeCache.current(),
        key1 = appContext,
    ) {
        value = TrafficMapCountryShapeCache.load(appContext)
    }
    return shapes
}

internal suspend fun prewarmTrafficMapCountryShapes(context: Context): Int {
    val appContext = context.applicationContext
    val shapes = TrafficMapCountryShapeCache.load(appContext)
    TrafficMapLandLayerCache.prewarm(
        shapes = shapes,
        displayMetrics = appContext.resources.displayMetrics,
    )
    return shapes.size
}

private object TrafficMapCountryShapeCache {
    private val mutex = Mutex()

    @Volatile
    private var cachedShapes: List<TrafficMapCountryShape>? = null

    fun current(): List<TrafficMapCountryShape> = cachedShapes.orEmpty()

    suspend fun load(context: Context): List<TrafficMapCountryShape> {
        cachedShapes?.let { shapes -> return shapes }
        return mutex.withLock {
            cachedShapes?.let { shapes -> return@withLock shapes }
            val startedAtMs = SystemClock.elapsedRealtime()
            withContext(TrafficMapRenderDispatcher.dispatcher) {
                context.assets.open(TRAFFIC_MAP_COUNTRY_SHAPES_ASSET).use { inputStream ->
                    TrafficMapCountryShapeAssetParser().parse(inputStream)
                }
            }.also { shapes ->
                cachedShapes = shapes
                val finishedAtMs = SystemClock.elapsedRealtime()
                logTrafficMapDebug(
                    "shapes loaded count=${shapes.size} streamParseMs=${finishedAtMs - startedAtMs}",
                )
            }
        }
    }
}

private data class DrawableTrafficMapDestination(
    val countryCode: String,
    val lat: Double,
    val lon: Double,
    val bytes: Long,
    val role: TrafficMapPointRole,
)

private data class DrawableTrafficMapEdge(
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val bytes: Long,
    val role: TrafficMapEdgeRole,
)

private data class TrafficMapRouteDrawModel(
    val path: Path,
    val strokeWidth: Float,
    val alpha: Float,
    val role: TrafficMapEdgeRole,
)

private fun List<TrafficMapPoint>.toDrawableTrafficMapDestinations(): List<DrawableTrafficMapDestination> =
    map(TrafficMapPoint::toDrawableTrafficMapDestination)

private fun TrafficMapPoint.toDrawableTrafficMapDestination(): DrawableTrafficMapDestination =
    DrawableTrafficMapDestination(
        countryCode = countryCode.uppercase(Locale.US),
        lat = lat,
        lon = lon,
        bytes = bytes.coerceAtLeast(1L),
        role = role,
    )

private fun List<TrafficMapEdge>.toDrawableTrafficMapEdges(): List<DrawableTrafficMapEdge> =
    map { edge ->
        DrawableTrafficMapEdge(
            fromLat = edge.fromLat,
            fromLon = edge.fromLon,
            toLat = edge.toLat,
            toLon = edge.toLon,
            bytes = edge.bytes.coerceAtLeast(1L),
            role = edge.role,
        )
    }

@Composable
private fun TrafficMapLegend(
    state: TrafficMapUiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = trafficMapColors()
    val destinations = remember(state.destinations) { state.destinations }
    val originLabel = remember(state.originCity, state.originCountryName, state.originCountryCode) { state.originLocationLabel() }
    val routePoints = remember(state.vpnRoute, state.torExit) { listOfNotNull(state.vpnRoute, state.torExit) }
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (routePoints.isEmpty()) {
            TrafficMapOriginRow(
                originLabel = originLabel,
                showIcon = state.originCountryCode != null,
                colors = colors,
            )
        }
        if (destinations.isEmpty() && routePoints.isEmpty()) {
            Text(
                modifier = Modifier.weight(1f),
                text =
                    stringResource(
                        if (state.isAvailable) {
                            R.string.traffic_map_waiting_connections
                        } else {
                            R.string.traffic_map_live_requires_firewall
                        },
                    ),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                ),
                color = colors.inactiveText,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                state.vpnRoute?.let { point ->
                    TrafficMapLegendDestinationRow(
                        context = context,
                        point = point,
                        markerColor = colors.vpnRoute,
                        textColor = colors.legendText,
                        showMetrics = false,
                    )
                }
                state.torExit?.let { point ->
                    TrafficMapLegendDestinationRow(
                        context = context,
                        point = point,
                        markerColor = colors.torExit,
                        textColor = colors.legendText,
                        showMetrics = false,
                    )
                }
                destinations.forEach { point ->
                    TrafficMapLegendDestinationRow(
                        context = context,
                        point = point,
                        markerColor = colors.destination,
                        textColor = colors.legendText,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficMapOriginRow(
    originLabel: String,
    showIcon: Boolean,
    colors: TrafficMapColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            Icon(
                imageVector = Icons.Outlined.PhoneAndroid,
                contentDescription = stringResource(R.string.traffic_map_device_location_icon),
                tint = colors.origin,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = originLabel,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp,
            ),
            fontWeight = FontWeight.Bold,
            color = colors.legendText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun curvedTrafficRoutePath(
    from: Offset,
    to: Offset,
    lane: Int,
): Path {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val distance = sqrt((dx * dx) + (dy * dy))
    return Path().apply {
        moveTo(from.x, from.y)
        if (distance < 1f) {
            lineTo(to.x, to.y)
            return@apply
        }
        val bendLane = lane.takeIf { it != 0 } ?: 1
        val bend = min(distance * (0.15f + (0.035f * (kotlin.math.abs(bendLane) - 1).coerceAtMost(3))), 58f) *
            bendLane.signFloat()
        val midX = (from.x + to.x) / 2f
        val midY = (from.y + to.y) / 2f
        val controlX = midX - (dy / distance * bend)
        val controlY = midY + (dx / distance * bend)
        quadraticTo(controlX, controlY, to.x, to.y)
    }
}

private fun trafficRouteLanes(
    origin: Offset,
    edges: List<DrawableTrafficMapEdge>,
    viewport: TrafficMapViewport,
): Map<String, Int> {
    val countsByBucket = mutableMapOf<Int, Int>()
    return edges.associate { edge ->
        val point = project(edge.toLat, edge.toLon, viewport)
        val angle = atan2(point.y - origin.y, point.x - origin.x)
        val bucket = floor((angle + TRAFFIC_ROUTE_PI) / TRAFFIC_ROUTE_ANGLE_BUCKET_RADIANS).toInt()
        val indexInBucket = countsByBucket[bucket] ?: 0
        countsByBucket[bucket] = indexInBucket + 1
        trafficMapEdgeLaneKey(edge) to routeLane(bucket, indexInBucket)
    }
}

private fun trafficMapEdgeLaneKey(edge: DrawableTrafficMapEdge): String =
    "${edge.role}:${edge.toLat}:${edge.toLon}"

private fun routeLane(
    bucket: Int,
    indexInBucket: Int,
): Int {
    val magnitude = (indexInBucket / 2) + 1
    val sign = if ((bucket + indexInBucket) % 2 == 0) 1 else -1
    return sign * magnitude
}

private fun Int.signFloat(): Float = if (this < 0) -1f else 1f

private fun TrafficMapUiState.originLocationLabel(): String =
    listOfNotNull(
        originCountryName?.let { countryName -> "${countryEmoji(originCountryCode)} $countryName" }
            ?: originCountryCode?.let(::countryEmoji),
        originCity?.takeIf(String::isNotBlank),
    )
        .joinToString(separator = "\n")
        .ifBlank { "IP" }

@Composable
private fun TrafficMapLegendDestinationRow(
    context: Context,
    point: TrafficMapPoint,
    markerColor: Color,
    textColor: Color,
    showMetrics: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(0.58f),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
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
                    color = markerColor,
                ) {}
            }
            Text(
                text = countryEmoji(point.countryCode),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                ),
                maxLines = 1,
            )
            TrafficMapLegendCell(
                text = point.countryCode.uppercase(Locale.US),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                color = textColor,
            )
        }
        TrafficMapLegendCell(
            text = if (showMetrics) point.connections.toString() else "",
            modifier = Modifier.weight(0.62f),
            textAlign = TextAlign.End,
            color = textColor,
        )
        TrafficMapLegendCell(
            text = if (showMetrics) formatBytes(context, point.bytes) else "",
            modifier = Modifier.weight(0.82f),
            textAlign = TextAlign.End,
            color = textColor,
        )
    }
}

@Composable
private fun TrafficMapLegendCell(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign,
    fontWeight: FontWeight = FontWeight.Normal,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 12.sp,
        ),
        fontWeight = fontWeight,
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun rememberTrafficMapPowerState(): TrafficMapPowerState {
    val appContext = LocalContext.current.applicationContext
    val powerManager = remember(appContext) { appContext.getSystemService<PowerManager>() }
    val state by produceState(
        initialValue =
            TrafficMapPowerState(
                powerSaveMode = powerManager?.isPowerSaveMode == true,
                batteryPercent = null,
            ),
        key1 = appContext,
        key2 = powerManager,
    ) {
        if (TRAFFIC_MAP_POWER_STATE_STARTUP_DELAY_MS > 0L) {
            delay(TRAFFIC_MAP_POWER_STATE_STARTUP_DELAY_MS)
        }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    value = resolveTrafficMapPowerState(appContext, powerManager, intent)
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
        val stickyIntent =
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        value = resolveTrafficMapPowerState(appContext, powerManager, stickyIntent)
        try {
            awaitCancellation()
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }
    return state
}

private fun resolveTrafficMapPowerState(
    context: Context,
    powerManager: PowerManager?,
    batteryIntent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)),
): TrafficMapPowerState =
    TrafficMapPowerState(
        powerSaveMode = powerManager?.isPowerSaveMode == true,
        batteryPercent = batteryIntent?.batteryPercent(),
    )

private fun Intent.batteryPercent(): Int? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) {
        return null
    }
    return ((level * 100f) / scale).toInt()
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

private fun androidTrafficMapLandPath(
    shapes: List<TrafficMapCountryShape>,
    viewport: TrafficMapViewport,
): AndroidPath =
    AndroidPath().apply {
        shapes.forEach { shape ->
            shape.rings.forEach { ring ->
                ring.forEachIndexed { index, point ->
                    val offset = project(lat = point.lat, lon = point.lon, viewport = viewport)
                    if (index == 0) {
                        moveTo(offset.x, offset.y)
                    } else {
                        lineTo(offset.x, offset.y)
                    }
                }
                close()
            }
        }
    }

private fun trafficMapLandBitmap(
    size: Size,
    shapes: List<TrafficMapCountryShape>,
    viewport: TrafficMapViewport,
    color: Color,
): ImageBitmap {
    val width = size.width.toInt().coerceAtLeast(1)
    val height = size.height.toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    AndroidCanvas(bitmap).drawPath(
        androidTrafficMapLandPath(shapes, viewport),
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.FILL
            this.color = color.toArgb()
        },
    )
    return bitmap.asImageBitmap()
}

private object TrafficMapLandLayerCache {
    private const val MAX_ENTRIES = 8
    private const val SIZE_BUCKET_PX = 32
    private val lock = Any()
    private var latestBitmap: ImageBitmap? = null
    private val inFlight =
        mutableMapOf<TrafficMapLandLayerKey, kotlinx.coroutines.CompletableDeferred<ImageBitmap>>()
    private val bitmaps =
        LinkedHashMap<TrafficMapLandLayerKey, ImageBitmap>(
            MAX_ENTRIES,
            0.75f,
            true,
        )

    fun currentBitmap(
        shapes: List<TrafficMapCountryShape>,
        canvasSize: IntSize,
        color: Color,
    ): ImageBitmap? {
        val key = landLayerKey(shapes = shapes, canvasSize = canvasSize, color = color)
        synchronized(lock) {
            return bitmaps[key]
        }
    }

    fun latestBitmap(): ImageBitmap? =
        synchronized(lock) {
            latestBitmap
        }

    suspend fun bitmap(
        shapes: List<TrafficMapCountryShape>,
        canvasSize: IntSize,
        color: Color,
    ): ImageBitmap =
        withContext(TrafficMapRenderDispatcher.dispatcher) {
            val startedAtMs = SystemClock.elapsedRealtime()
            val key = landLayerKey(shapes = shapes, canvasSize = canvasSize, color = color)
            val owner = kotlinx.coroutines.CompletableDeferred<ImageBitmap>()
            var shouldRender = false
            val deferred =
                synchronized(lock) {
                    bitmaps[key]?.let { bitmap -> return@withContext bitmap }
                    inFlight[key] ?: owner.also { pending ->
                        inFlight[key] = pending
                        shouldRender = true
                    }
                }
            if (!shouldRender) {
                return@withContext deferred.await()
            }
            val size = Size(key.width.toFloat(), key.height.toFloat())
            var completed = false
            try {
                val bitmap =
                    trafficMapLandBitmap(
                        size = size,
                        shapes = shapes,
                        viewport = TrafficMapViewport(topLeft = Offset.Zero, size = size),
                        color = color,
                    )
                synchronized(lock) {
                    storeBitmapLocked(key = key, bitmap = bitmap)
                    inFlight.remove(key)
                }
                owner.complete(bitmap)
                completed = true
                logTrafficMapDebug(
                    "land bitmap ready width=${key.width} height=${key.height} shapes=${key.shapeCount} durationMs=${SystemClock.elapsedRealtime() - startedAtMs}",
                )
                bitmap
            } finally {
                if (!completed) {
                    synchronized(lock) {
                        inFlight.remove(key)
                    }
                    owner.cancel()
                }
            }
        }

    suspend fun prewarm(
        shapes: List<TrafficMapCountryShape>,
        displayMetrics: DisplayMetrics,
    ) {
        if (shapes.isEmpty()) {
            return
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        var renderedCount = 0
        withContext(TrafficMapRenderDispatcher.dispatcher) {
            val primaryCanvasSize = trafficMapPrimaryPrewarmCanvasSize(displayMetrics)
            val deferredCanvasSizes = trafficMapPrewarmCanvasSizes(displayMetrics) - primaryCanvasSize
            renderedCount += prewarmBitmapIfMissing(shapes = shapes, canvasSize = primaryCanvasSize)
            deferredCanvasSizes.forEach { canvasSize ->
                renderedCount += prewarmBitmapIfMissing(shapes = shapes, canvasSize = canvasSize)
            }
        }
        if (renderedCount > 0) {
            logTrafficMapDebug(
                "land bitmap prewarmed count=$renderedCount durationMs=${SystemClock.elapsedRealtime() - startedAtMs}",
            )
        }
    }

    private fun landLayerKey(
        shapes: List<TrafficMapCountryShape>,
        canvasSize: IntSize,
        color: Color,
    ): TrafficMapLandLayerKey {
        val bitmapSize = trafficMapLandLayerBitmapSize(canvasSize)
        return TrafficMapLandLayerKey(
            width = bucketDimension(bitmapSize.width),
            height = bucketDimension(bitmapSize.height),
            color = color.toArgb(),
            shapesIdentity = System.identityHashCode(shapes),
            shapeCount = shapes.size,
        )
    }

    private fun bucketDimension(value: Int): Int =
        (((value.coerceAtLeast(1) + SIZE_BUCKET_PX - 1) / SIZE_BUCKET_PX) * SIZE_BUCKET_PX)

    fun bucketDimensionForPrewarm(value: Int): Int = bucketDimension(value)

    private fun prewarmBitmapIfMissing(
        shapes: List<TrafficMapCountryShape>,
        canvasSize: IntSize,
    ): Int {
        val key =
            landLayerKey(
                shapes = shapes,
                canvasSize = canvasSize,
                color = TRAFFIC_MAP_DEFAULT_COUNTRY_FILL,
            )
        val owner = kotlinx.coroutines.CompletableDeferred<ImageBitmap>()
        synchronized(lock) {
            if (bitmaps.containsKey(key) || inFlight.containsKey(key)) {
                return 0
            }
            inFlight[key] = owner
        }
        val size = Size(key.width.toFloat(), key.height.toFloat())
        var completed = false
        try {
            val bitmap =
                trafficMapLandBitmap(
                    size = size,
                    shapes = shapes,
                    viewport = TrafficMapViewport(topLeft = Offset.Zero, size = size),
                    color = TRAFFIC_MAP_DEFAULT_COUNTRY_FILL,
                )
            synchronized(lock) {
                storeBitmapLocked(key = key, bitmap = bitmap)
                inFlight.remove(key)
            }
            owner.complete(bitmap)
            completed = true
            return 1
        } finally {
            if (!completed) {
                synchronized(lock) {
                    inFlight.remove(key)
                }
                owner.cancel()
            }
        }
    }

    private fun storeBitmapLocked(
        key: TrafficMapLandLayerKey,
        bitmap: ImageBitmap,
    ) {
        bitmaps[key] = bitmap
        latestBitmap = bitmap
        while (bitmaps.size > MAX_ENTRIES) {
            val eldest = bitmaps.entries.firstOrNull()?.key ?: break
            bitmaps.remove(eldest)
        }
    }
}

internal fun trafficMapLandLayerBitmapSize(canvasSize: IntSize): IntSize {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) {
        return IntSize.Zero
    }
    val viewport =
        trafficMapViewport(
            Size(
                width = canvasSize.width.toFloat(),
                height = canvasSize.height.toFloat(),
            ),
        )
    return IntSize(
        width = viewport.size.width.roundToInt().coerceAtLeast(1),
        height = viewport.size.height.roundToInt().coerceAtLeast(1),
    )
}

internal fun trafficMapPrewarmCanvasSizes(displayMetrics: DisplayMetrics): List<IntSize> {
    val shortSide = min(displayMetrics.widthPixels, displayMetrics.heightPixels).coerceAtLeast(1)
    return TRAFFIC_MAP_PREWARM_WIDTH_FRACTIONS
        .map { fraction -> trafficMapPrewarmCanvasSize(shortSide, fraction) }
        .distinct()
}

internal fun trafficMapPrimaryPrewarmCanvasSize(displayMetrics: DisplayMetrics): IntSize {
    val shortSide = min(displayMetrics.widthPixels, displayMetrics.heightPixels).coerceAtLeast(1)
    return trafficMapPrewarmCanvasSize(shortSide, TRAFFIC_MAP_PREWARM_PRIMARY_WIDTH_FRACTION)
}

private fun trafficMapPrewarmCanvasSize(
    shortSide: Int,
    fraction: Float,
): IntSize {
    val width = (shortSide * fraction).roundToInt()
    val bucketedWidth = TrafficMapLandLayerCache.bucketDimensionForPrewarm(width)
    val bucketedHeight =
        TrafficMapLandLayerCache.bucketDimensionForPrewarm(
            (bucketedWidth / TRAFFIC_MAP_WORLD_ASPECT_RATIO).roundToInt(),
        )
    return IntSize(width = bucketedWidth, height = bucketedHeight)
}

private data class TrafficMapLandLayerKey(
    val width: Int,
    val height: Int,
    val color: Int,
    val shapesIdentity: Int,
    val shapeCount: Int,
)

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

@Immutable
internal data class TrafficMapColors(
    val countryFill: Color,
    val routeLine: Color,
    val routeHalo: Color,
    val destination: Color,
    val vpnRoute: Color,
    val torExit: Color,
    val origin: Color,
    val phoneScreen: Color,
    val legendText: Color,
    val inactiveText: Color,
)

@Composable
private fun trafficMapColors(): TrafficMapColors {
    val colorScheme = MaterialTheme.colorScheme
    val semanticColors = LocalFoxholeSemanticColors.current
    return trafficMapColors(
        darkTheme = LocalFoxholeDarkTheme.current,
        surfaceColor = colorScheme.surface,
        surfaceVariantColor = colorScheme.surfaceVariant,
        onSurfaceVariantColor = colorScheme.onSurfaceVariant,
        successColor = semanticColors.success,
        accentColor = colorScheme.primary,
    )
}

internal fun trafficMapColors(
    darkTheme: Boolean,
    surfaceColor: Color,
    surfaceVariantColor: Color,
    onSurfaceVariantColor: Color,
    successColor: Color,
    accentColor: Color,
): TrafficMapColors {
    val landBaseAlpha = if (darkTheme) 0.46f else 0.58f
    val landDetailAlpha = if (darkTheme) 0.22f else 0.30f
    val countryBase = surfaceVariantColor.copy(alpha = landBaseAlpha).compositeOver(surfaceColor)
    val countryFill = onSurfaceVariantColor.copy(alpha = landDetailAlpha).compositeOver(countryBase)
    val routeHalo =
        if (darkTheme) {
            surfaceColor
        } else {
            Color.White
        }
    val legendText = onSurfaceVariantColor.copy(alpha = if (darkTheme) 0.88f else 0.92f)
    return TrafficMapColors(
        countryFill = countryFill,
        routeLine =
            if (darkTheme) {
                Color.White.copy(alpha = 0.88f)
            } else {
                Color(0xFFFAFAFA)
            },
        routeHalo = routeHalo,
        destination = onSurfaceVariantColor.copy(alpha = if (darkTheme) 0.78f else 0.70f),
        vpnRoute = successColor,
        torExit = Color(0xFFFF8A3D),
        origin = accentColor,
        phoneScreen = surfaceColor.copy(alpha = if (darkTheme) 0.92f else 0.94f),
        legendText = legendText,
        inactiveText = onSurfaceVariantColor.copy(alpha = if (darkTheme) 0.72f else 0.76f),
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

private data class TrafficMapViewport(
    val topLeft: Offset,
    val size: Size,
)

@Immutable
private data class TrafficMapPowerState(
    val powerSaveMode: Boolean,
    val batteryPercent: Int?,
) {
    val mapDisabled: Boolean
        get() = powerSaveMode || (batteryPercent != null && batteryPercent < TRAFFIC_MAP_LOW_BATTERY_PERCENT)
}

private val TRAFFIC_MAP_CARD_TOTAL_HEIGHT = 184.dp
private val TRAFFIC_MAP_DEFAULT_COUNTRY_FILL = Color(0xFF3E3F41)
private const val TRAFFIC_MAP_ROUTE_MIN_STROKE_DP = 0.35f
private const val TRAFFIC_MAP_ROUTE_MAX_STROKE_DP = 0.72f
private const val TRAFFIC_MAP_ROUTE_MIN_ALPHA = 0.32f
private const val TRAFFIC_MAP_ROUTE_ALPHA_RANGE = 0.18f
private const val TRAFFIC_MAP_ROUTE_HALO_STROKE_EXTRA_DP = 0.2f
private const val TRAFFIC_MAP_ROUTE_HALO_ALPHA_MULTIPLIER = 0.08f
private const val TRAFFIC_MAP_HEAVY_CONTENT_SETTLE_DELAY_MS = 0L
private const val TRAFFIC_MAP_POWER_STATE_STARTUP_DELAY_MS = 0L
private const val TRAFFIC_MAP_WEIGHT = 0.74f
private const val TRAFFIC_MAP_LEGEND_WEIGHT = 0.26f
private const val TRAFFIC_MAP_WORLD_ASPECT_RATIO = 2f
private const val TRAFFIC_MAP_MIN_LAT = -55.0
private const val TRAFFIC_MAP_MAX_LAT = 85.0
private const val TRAFFIC_MAP_LAT_RANGE = TRAFFIC_MAP_MAX_LAT - TRAFFIC_MAP_MIN_LAT
private const val MAX_TRAFFIC_MAP_DRAW_EDGES = 30
private const val MAX_TRAFFIC_MAP_DRAW_DESTINATIONS = 30
private const val TRAFFIC_MAP_LOW_BATTERY_PERCENT = 10
private const val TRAFFIC_MAP_PREWARM_COMPACT_WIDTH_FRACTION = 0.58f
private const val TRAFFIC_MAP_PREWARM_PRIMARY_WIDTH_FRACTION = 0.65f
private const val TRAFFIC_MAP_PREWARM_WIDE_WIDTH_FRACTION = 0.74f
private const val TRAFFIC_ROUTE_PI = 3.141592653589793
private const val TRAFFIC_ROUTE_ANGLE_BUCKET_RADIANS = 0.17453292519943295
private const val TRAFFIC_MAP_COUNTRY_SHAPES_ASSET = "maps/ne_110m_admin_0_countries_preprocessed.json"
private const val TRAFFIC_MAP_LOG_TAG = "FoxholeDiag"
private val TRAFFIC_MAP_EUROPE_COUNTRY_CODES =
    setOf(
        "CH",
        "DE",
        "ES",
        "FI",
        "FR",
        "GB",
        "IE",
        "IT",
        "NL",
        "NO",
        "PL",
        "RO",
        "SE",
        "TR",
        "UA",
    )
private val TRAFFIC_MAP_PREWARM_WIDTH_FRACTIONS =
    floatArrayOf(
        TRAFFIC_MAP_PREWARM_COMPACT_WIDTH_FRACTION,
        TRAFFIC_MAP_PREWARM_PRIMARY_WIDTH_FRACTION,
        TRAFFIC_MAP_PREWARM_WIDE_WIDTH_FRACTION,
    )

private fun logTrafficMapDebug(message: String) {
    if (BuildConfig.DEBUG) {
        Log.d(TRAFFIC_MAP_LOG_TAG, "[traffic-map] $message")
    }
}
