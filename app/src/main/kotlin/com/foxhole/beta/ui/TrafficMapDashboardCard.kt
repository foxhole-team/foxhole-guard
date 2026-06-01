package com.foxhole.beta.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.foxhole.beta.R
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.traffic.TrafficMapCountryShape
import com.foxhole.beta.core.traffic.TrafficMapCountryShapeAssetParser
import com.foxhole.beta.ui.theme.LocalFoxholeDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

@Composable
internal fun TrafficMapDashboardCard(
    state: TrafficMapUiState,
    modifier: Modifier = Modifier,
    legendLoading: Boolean = false,
) {
    val powerState = rememberTrafficMapPowerState()
    var forceMapEnabled by rememberSaveable { mutableStateOf(false) }
    val mapDisabledForPower = powerState.mapDisabled && !forceMapEnabled
    val countryShapes = rememberTrafficMapCountryShapes()
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
                if (legendLoading) {
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
private fun TrafficMapLegendLoadingBlock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FoxholeSkeletonBlock(modifier = Modifier.size(16.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                FoxholeSkeletonBlock(modifier = Modifier.width(56.dp).height(9.dp))
                FoxholeSkeletonBlock(modifier = Modifier.width(42.dp).height(8.dp))
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            FoxholeSkeletonBlock(modifier = Modifier.fillMaxWidth(0.92f).height(8.dp))
            FoxholeSkeletonBlock(modifier = Modifier.fillMaxWidth(0.74f).height(8.dp))
            FoxholeSkeletonBlock(modifier = Modifier.fillMaxWidth(0.52f).height(8.dp))
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
@Suppress("LongMethod")
private fun TrafficMapCanvas(
    state: TrafficMapUiState,
    countryShapes: List<TrafficMapCountryShape>,
    modifier: Modifier = Modifier,
) {
    val latestState = rememberUpdatedState(state)
    val colors = trafficMapColors()
    val mapCountryShapes = countryShapes

    Box(
        modifier = modifier
            .drawWithCache {
                val viewport = trafficMapViewport(size)
                val countryPath = trafficMapLandPath(mapCountryShapes, viewport)
                val countryBitmap =
                    trafficMapLandBitmap(
                        size = size,
                        countryPath = countryPath,
                        color = colors.countryBorder.copy(alpha = 0.44f),
                    )
                val maxLineStroke = 2.1.dp.toPx()
                val minLineStroke = 0.65.dp.toPx()
                val destinationRadius = 3.3.dp.toPx()
                val glowRadius = 8.dp.toPx()
                val phoneWidth = 8.dp.toPx()
                val phoneHeight = 12.dp.toPx()
                val phoneCorner = CornerRadius(2.2.dp.toPx(), 2.2.dp.toPx())
                val phoneScreenInset = 1.4.dp.toPx()
                val phoneHomeRadius = 0.65.dp.toPx()

                onDrawBehind {
                    val mapState = latestState.value
                    val drawableDestinations = mapState.destinations.toDrawableTrafficMapDestinations()
                    drawImage(countryBitmap)

                    val origin = project(mapState.originLat, mapState.originLon, viewport)
                    val maxBytes = drawableDestinations.maxOfOrNull { destination -> destination.bytes }?.coerceAtLeast(1L) ?: 1L
                    val routeLanes = trafficRouteLanes(origin, drawableDestinations, viewport)
                    val edgeCount = min(drawableDestinations.size, MAX_TRAFFIC_MAP_DRAW_EDGES)
                    for (index in 0 until edgeCount) {
                        val destination = drawableDestinations[index]
                        val to = project(destination.lat, destination.lon, viewport)
                        val weight = sqrt(destination.bytes.toDouble() / maxBytes.toDouble()).toFloat()
                        drawPath(
                            path =
                                curvedTrafficRoutePath(
                                    from = origin,
                                    to = to,
                                    lane = routeLanes[destination.countryCode] ?: 1,
                                ),
                            color = colors.routeLine.copy(alpha = 0.22f + (0.24f * weight)),
                            style =
                                Stroke(
                                    width = minLineStroke + ((maxLineStroke - minLineStroke) * weight),
                                    cap = StrokeCap.Round,
                                ),
                        )
                    }

                    val destinationCount = min(drawableDestinations.size, MAX_TRAFFIC_MAP_DRAW_DESTINATIONS)
                    for (index in 0 until destinationCount) {
                        val point = drawableDestinations[index]
                        val offset = project(point.lat, point.lon, viewport)
                        drawCircle(
                            color = colors.destination.copy(alpha = 0.18f),
                            radius = glowRadius,
                            center = offset,
                        )
                        drawCircle(
                            color = colors.destination,
                            radius = destinationRadius,
                            center = offset,
                        )
                    }

                    if (mapState.originCountryCode != null) {
                        val origin = project(mapState.originLat, mapState.originLon, viewport)
                        drawCircle(
                            color = colors.origin.copy(alpha = 0.18f),
                            radius = glowRadius,
                            center = origin,
                        )
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

private object TrafficMapCountryShapeCache {
    private val mutex = Mutex()

    @Volatile
    private var cachedShapes: List<TrafficMapCountryShape>? = null

    fun current(): List<TrafficMapCountryShape> = cachedShapes.orEmpty()

    suspend fun load(context: Context): List<TrafficMapCountryShape> {
        cachedShapes?.let { shapes -> return shapes }
        return mutex.withLock {
            cachedShapes?.let { shapes -> return@withLock shapes }
            val raw =
                withContext(Dispatchers.IO) {
                    context.assets.open(TRAFFIC_MAP_COUNTRY_SHAPES_ASSET)
                        .bufferedReader()
                        .use { reader -> reader.readText() }
                }
            withContext(Dispatchers.Default) {
                TrafficMapCountryShapeAssetParser()
                    .parse(raw)
            }.also { shapes ->
                cachedShapes = shapes
            }
        }
    }
}

private data class DrawableTrafficMapDestination(
    val countryCode: String,
    val lat: Double,
    val lon: Double,
    val bytes: Long,
)

private fun List<TrafficMapPoint>.toDrawableTrafficMapDestinations(): List<DrawableTrafficMapDestination> =
    map { point ->
        DrawableTrafficMapDestination(
            countryCode = point.countryCode.uppercase(Locale.US),
            lat = point.lat,
            lon = point.lon,
            bytes = point.bytes.coerceAtLeast(1L),
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
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TrafficMapOriginRow(
            originLabel = originLabel,
            showIcon = state.originCountryCode != null,
        )
        if (destinations.isEmpty()) {
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
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                TrafficMapLegendHeader()
                destinations.forEach { point ->
                    TrafficMapLegendDestinationRow(context = context, point = point, markerColor = colors.destination)
                }
            }
        }
    }
}

@Composable
private fun TrafficMapOriginRow(
    originLabel: String,
    showIcon: Boolean,
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
                tint = MaterialTheme.colorScheme.primary,
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
            color = MaterialTheme.colorScheme.onSurface,
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
    destinations: List<DrawableTrafficMapDestination>,
    viewport: TrafficMapViewport,
): Map<String, Int> {
    val countsByBucket = mutableMapOf<Int, Int>()
    return destinations.associate { destination ->
        val point = project(destination.lat, destination.lon, viewport)
        val angle = atan2(point.y - origin.y, point.x - origin.x)
        val bucket = floor((angle + TRAFFIC_ROUTE_PI) / TRAFFIC_ROUTE_ANGLE_BUCKET_RADIANS).toInt()
        val indexInBucket = countsByBucket[bucket] ?: 0
        countsByBucket[bucket] = indexInBucket + 1
        destination.countryCode to routeLane(bucket, indexInBucket)
    }
}

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
private fun TrafficMapLegendHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrafficMapLegendCell(
            text = stringResource(R.string.traffic_map_country_header),
            modifier = Modifier.weight(0.58f),
            textAlign = TextAlign.Start,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TrafficMapLegendCell(
            text = stringResource(R.string.traffic_map_sessions_header),
            modifier = Modifier.weight(0.62f),
            textAlign = TextAlign.End,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TrafficMapLegendCell(
            text = stringResource(R.string.traffic_map_total_header),
            modifier = Modifier.weight(0.82f),
            textAlign = TextAlign.End,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrafficMapLegendDestinationRow(
    context: Context,
    point: TrafficMapPoint,
    markerColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(0.58f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
            TrafficMapLegendCell(
                text = point.countryCode.uppercase(Locale.US),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
        }
        TrafficMapLegendCell(
            text = point.connections.toString(),
            modifier = Modifier.weight(0.62f),
            textAlign = TextAlign.End,
        )
        TrafficMapLegendCell(
            text = formatBytes(context, point.bytes),
            modifier = Modifier.weight(0.82f),
            textAlign = TextAlign.End,
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
            fontSize = 8.5.sp,
            lineHeight = 10.sp,
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
    var state by remember(appContext, powerManager) {
        mutableStateOf(resolveTrafficMapPowerState(appContext, powerManager))
    }
    DisposableEffect(appContext, powerManager) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    state = resolveTrafficMapPowerState(appContext, powerManager, intent)
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
        state = resolveTrafficMapPowerState(appContext, powerManager, stickyIntent)
        onDispose { runCatching { appContext.unregisterReceiver(receiver) } }
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

private fun trafficMapLandPath(
    shapes: List<TrafficMapCountryShape>,
    viewport: TrafficMapViewport,
): Path =
    Path().apply {
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
    countryPath: Path,
    color: Color,
): ImageBitmap {
    val width = size.width.toInt().coerceAtLeast(1)
    val height = size.height.toInt().coerceAtLeast(1)
    return ImageBitmap(width = width, height = height, config = ImageBitmapConfig.Argb8888).also { bitmap ->
        Canvas(bitmap).drawPath(
            path = countryPath,
            paint =
                Paint().apply {
                    this.color = color
                },
        )
    }
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

@Immutable
private data class TrafficMapColors(
    val countryBorder: Color,
    val routeLine: Color,
    val destination: Color,
    val origin: Color,
    val phoneScreen: Color,
)

@Composable
private fun trafficMapColors(): TrafficMapColors {
    val colorScheme = MaterialTheme.colorScheme
    return if (LocalFoxholeDarkTheme.current) {
        TrafficMapColors(
            countryBorder = Color(0xFFA0ABA5),
            routeLine = FoxholePositiveAccent,
            destination = FoxholePositiveAccent,
            origin = FoxholePositiveAccent,
            phoneScreen = colorScheme.surface.copy(alpha = 0.92f),
        )
    } else {
        TrafficMapColors(
            countryBorder = Color(0xFF56635E),
            routeLine = Color(0xFF278A5B),
            destination = Color(0xFF278A5B),
            origin = Color(0xFF278A5B),
            phoneScreen = colorScheme.surface.copy(alpha = 0.94f),
        )
    }
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
private const val TRAFFIC_MAP_WEIGHT = 0.74f
private const val TRAFFIC_MAP_LEGEND_WEIGHT = 0.26f
private const val TRAFFIC_MAP_WORLD_ASPECT_RATIO = 2f
private const val TRAFFIC_MAP_MIN_LAT = -55.0
private const val TRAFFIC_MAP_MAX_LAT = 85.0
private const val TRAFFIC_MAP_LAT_RANGE = TRAFFIC_MAP_MAX_LAT - TRAFFIC_MAP_MIN_LAT
private const val MAX_TRAFFIC_MAP_DRAW_EDGES = 30
private const val MAX_TRAFFIC_MAP_DRAW_DESTINATIONS = 30
private const val TRAFFIC_MAP_LOW_BATTERY_PERCENT = 10
private const val TRAFFIC_ROUTE_PI = 3.141592653589793
private const val TRAFFIC_ROUTE_ANGLE_BUCKET_RADIANS = 0.17453292519943295
private const val TRAFFIC_MAP_COUNTRY_SHAPES_ASSET = "maps/ne_110m_admin_0_countries_preprocessed.json"
