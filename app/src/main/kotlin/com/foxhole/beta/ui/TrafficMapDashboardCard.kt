@file:Suppress("TooManyFunctions")

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
import android.os.Trace
import android.util.DisplayMetrics
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.foxhole.beta.core.model.CountryTrafficRole
import com.foxhole.beta.core.model.TrafficMapCountryVisual
import com.foxhole.beta.core.model.TrafficMapEdge
import com.foxhole.beta.core.model.TrafficMapEdgeRole
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapPointRole
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.traffic.TRAFFIC_MAP_COUNTRY_SHAPES_ASSET
import com.foxhole.beta.core.traffic.TrafficMapCountryShape
import com.foxhole.beta.core.traffic.TrafficMapCountryShapeAssetParser
import com.foxhole.beta.core.traffic.projectedCalloutAnchor
import com.foxhole.beta.core.traffic.requiresProjectedCallout
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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
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
    onOpenDetails: (() -> Unit)? = null,
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    TrafficMapDashboardCard(
        state = state,
        modifier = modifier,
        contentReady = contentReady,
        legendLoading = legendLoading,
        onOpenDetails = onOpenDetails,
    )
}

@Composable
internal fun TrafficMapDashboardCard(
    state: TrafficMapUiState,
    modifier: Modifier = Modifier,
    contentReady: Boolean = true,
    legendLoading: Boolean = false,
    onOpenDetails: (() -> Unit)? = null,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRAFFIC_MAP_CARD_TOTAL_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val colors = trafficMapColors()
            TrafficMapCardHeader(
                state = state,
                colors = colors,
                onOpenDetails = onOpenDetails,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier
                        .weight(TRAFFIC_MAP_WEIGHT)
                        .fillMaxHeight(),
                ) {
                    if (mapDisabledForPower) {
                        TrafficMapPowerSaveBlock(
                            onEnable = { forceMapEnabled = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (!heavyContentReady || countryShapesLoading) {
                        TrafficMapCanvasLoadingBlock(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("home_traffic_world_map_loading"),
                        )
                    } else {
                        TrafficMapCanvas(
                            state = state,
                            countryShapes = countryShapes,
                            modifier = Modifier
                                .fillMaxSize()
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
}

@Composable
private fun TrafficMapCardHeader(
    state: TrafficMapUiState,
    colors: TrafficMapColors,
    onOpenDetails: (() -> Unit)? = null,
) {
    val title = stringResource(R.string.traffic_map_title)
    val currentOriginLabel = remember(state.originCity, state.originCountryName, state.originCountryCode) {
        state.originLocationLabel()
            .takeIf {
                state.originCountryCode != null ||
                    !state.originCountryName.isNullOrBlank() ||
                    !state.originCity.isNullOrBlank()
            }
    }
    var retainedOriginLabel by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(currentOriginLabel) {
        if (currentOriginLabel != null) {
            retainedOriginLabel = currentOriginLabel
        }
    }
    val visibleOriginLabel = currentOriginLabel ?: retainedOriginLabel
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(TRAFFIC_MAP_WEIGHT),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Map,
                    contentDescription = null,
                    modifier =
                    Modifier
                        .padding(5.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onOpenDetails != null) {
                IconButton(
                    onClick = onOpenDetails,
                    modifier =
                        Modifier
                            .size(28.dp)
                            .testTag("home_traffic_map_details_action"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowOutward,
                        contentDescription = stringResource(R.string.traffic_map_open_details),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Box(
            modifier = Modifier.weight(TRAFFIC_MAP_LEGEND_WEIGHT),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier =
                    Modifier
                        .widthIn(min = 136.dp, max = 184.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = colors.origin,
                    modifier = Modifier.size(13.dp),
                )
                if (visibleOriginLabel != null) {
                    Text(
                        text = visibleOriginLabel,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = colors.legendText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    val shimmerProgress = rememberFoxholeSkeletonProgress()
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        FoxholeSkeletonBlock(
                            modifier = Modifier.width(86.dp).height(8.dp),
                            shimmerProgress = shimmerProgress,
                        )
                        FoxholeSkeletonBlock(
                            modifier = Modifier.width(62.dp).height(8.dp),
                            shimmerProgress = shimmerProgress,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrafficMapDetailScreen(
    stateFlow: StateFlow<TrafficMapUiState>,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    val countryShapes = rememberTrafficMapCountryShapes()
    val colors = trafficMapColors()
    FoxholeLazyScaffold(
        title = stringResource(R.string.traffic_map_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "traffic_map_detail_screen",
    ) {
        item(key = "traffic_map_detail_map") {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(244.dp)
                        .testTag("traffic_map_detail_map_surface"),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
            ) {
                if (countryShapes.isEmpty()) {
                    TrafficMapCanvasLoadingBlock(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .testTag("traffic_map_detail_world_map_loading"),
                    )
                } else {
                    TrafficMapCanvas(
                        state = state,
                        countryShapes = countryShapes,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .testTag("traffic_map_detail_world_map"),
                    )
                }
            }
        }
        item(key = "traffic_map_detail_table") {
            TrafficMapDetailCountryTable(
                state = state,
                colors = colors,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("traffic_map_detail_country_table"),
            )
        }
    }
}

@Composable
private fun TrafficMapDetailCountryTable(
    state: TrafficMapUiState,
    colors: TrafficMapColors,
    modifier: Modifier = Modifier,
) {
    var sort by rememberSaveable { mutableStateOf(TrafficMapDetailSort.TOTAL) }
    var range by rememberSaveable { mutableStateOf(TrafficMapDetailRange.TOP_30) }
    var filter by rememberSaveable { mutableStateOf(TrafficMapDetailFilter.ALL) }
    val detailPoints =
        remember(state.destinations, state.vpnRoute, state.torExit) {
            trafficMapDetailPoints(
                destinations = state.destinations,
                vpnRoute = state.vpnRoute,
                torExit = state.torExit,
            )
        }
    val newCountryCodes =
        remember(state.countryVisuals) {
            trafficMapNewCountryCodes(state.countryVisuals)
        }
    val filteredDestinations =
        remember(detailPoints, sort, filter, newCountryCodes) {
            trafficMapDetailDestinations(
                destinations = detailPoints,
                sort = sort,
                filter = filter,
                range = TrafficMapDetailRange.ALL,
                newCountryCodes = newCountryCodes,
            )
        }
    val destinations =
        remember(filteredDestinations, range, newCountryCodes) {
            trafficMapDetailDestinations(
                destinations = filteredDestinations,
                sort = TrafficMapDetailSort.COUNTRY,
                filter = TrafficMapDetailFilter.ALL,
                range = range,
                alreadySorted = true,
                newCountryCodes = newCountryCodes,
            )
        }
    val hiddenCountries =
        remember(filteredDestinations, destinations, state.hiddenCountryCount, filter) {
            trafficMapDetailHiddenCountries(
                filteredCount = filteredDestinations.size,
                visibleCount = destinations.size,
                repositoryHiddenCount = state.hiddenCountryCount,
                filter = filter,
            )
        }
    val showUnknownCountry =
        remember(state.unknownCountryBytes, state.unknownCountryConnections, filter) {
            trafficMapDetailShowUnknownCountry(
                unknownCountryBytes = state.unknownCountryBytes,
                unknownCountryConnections = state.unknownCountryConnections,
                filter = filter,
            )
        }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        TrafficMapDetailControls(
            sort = sort,
            onSortChange = { next -> sort = next },
            range = range,
            onRangeChange = { next -> range = next },
            filter = filter,
            onFilterChange = { next -> filter = next },
            colors = colors,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        TrafficMapDetailHeaderRow(colors = colors)
        if (destinations.isEmpty() && !showUnknownCountry) {
            TrafficMapEmptySummary(
                state = state,
                colors = colors,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            destinations.forEach { point ->
                TrafficMapDetailDestinationRow(
                    country = trafficMapDetailCountryLabel(point),
                    sessions = point.connections,
                    total = formatTrafficMapLegendBytes(point.bytes),
                    colors = colors,
                )
            }
            if (showUnknownCountry) {
                TrafficMapDetailDestinationRow(
                    country = stringResource(R.string.traffic_map_unknown_country),
                    sessions = state.unknownCountryConnections,
                    total = formatTrafficMapLegendBytes(state.unknownCountryBytes),
                    colors = colors,
                )
            }
            if (hiddenCountries > 0) {
                Text(
                    text = stringResource(R.string.traffic_map_more_countries, hiddenCountries),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inactiveText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.totalBytes > 0L || state.totalConnections > 0) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
            )
            TrafficMapDetailDestinationRow(
                country = stringResource(R.string.traffic_map_total_header),
                sessions = state.totalConnections,
                total = formatTrafficMapLegendBytes(state.totalBytes),
                colors = colors,
                strong = true,
            )
        }
    }
}

@Composable
private fun TrafficMapDetailControls(
    sort: TrafficMapDetailSort,
    onSortChange: (TrafficMapDetailSort) -> Unit,
    range: TrafficMapDetailRange,
    onRangeChange: (TrafficMapDetailRange) -> Unit,
    filter: TrafficMapDetailFilter,
    onFilterChange: (TrafficMapDetailFilter) -> Unit,
    colors: TrafficMapColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TrafficMapDetailControlRow(label = stringResource(R.string.traffic_map_sort_label), colors = colors) {
            TrafficMapDetailModeButton(
                text = stringResource(R.string.traffic_map_sort_total),
                selected = sort == TrafficMapDetailSort.TOTAL,
                onClick = { onSortChange(TrafficMapDetailSort.TOTAL) },
            )
            TrafficMapDetailModeButton(
                text = stringResource(R.string.traffic_map_sort_sessions),
                selected = sort == TrafficMapDetailSort.SESSIONS,
                onClick = { onSortChange(TrafficMapDetailSort.SESSIONS) },
            )
            TrafficMapDetailModeButton(
                text = stringResource(R.string.traffic_map_sort_country),
                selected = sort == TrafficMapDetailSort.COUNTRY,
                onClick = { onSortChange(TrafficMapDetailSort.COUNTRY) },
            )
        }
        TrafficMapDetailControlRow(label = stringResource(R.string.traffic_map_range_label), colors = colors) {
            TrafficMapDetailModeButton(
                text = stringResource(R.string.traffic_map_range_top_10),
                selected = range == TrafficMapDetailRange.TOP_10,
                onClick = { onRangeChange(TrafficMapDetailRange.TOP_10) },
            )
            TrafficMapDetailModeButton(
                text = stringResource(R.string.traffic_map_range_top_30),
                selected = range == TrafficMapDetailRange.TOP_30,
                onClick = { onRangeChange(TrafficMapDetailRange.TOP_30) },
            )
            TrafficMapDetailModeButton(
                text = stringResource(R.string.traffic_map_range_all),
                selected = range == TrafficMapDetailRange.ALL,
                onClick = { onRangeChange(TrafficMapDetailRange.ALL) },
            )
        }
        TrafficMapDetailControlRow(label = stringResource(R.string.traffic_map_filter_label), colors = colors) {
            TrafficMapDetailModeButton(
                text = stringResource(R.string.traffic_map_filter_all),
                selected = filter == TrafficMapDetailFilter.ALL,
                onClick = { onFilterChange(TrafficMapDetailFilter.ALL) },
            )
            TrafficMapDetailModeButton(
                text = stringResource(R.string.traffic_map_filter_vpn),
                selected = filter == TrafficMapDetailFilter.VPN,
                onClick = { onFilterChange(TrafficMapDetailFilter.VPN) },
            )
            TrafficMapDetailModeButton(
                text = stringResource(R.string.traffic_map_filter_tor),
                selected = filter == TrafficMapDetailFilter.TOR,
                onClick = { onFilterChange(TrafficMapDetailFilter.TOR) },
            )
            TrafficMapDetailModeButton(
                text = stringResource(R.string.traffic_map_filter_direct),
                selected = filter == TrafficMapDetailFilter.DIRECT,
                onClick = { onFilterChange(TrafficMapDetailFilter.DIRECT) },
            )
            TrafficMapDetailModeButton(
                text = stringResource(R.string.traffic_map_filter_new),
                selected = filter == TrafficMapDetailFilter.NEW,
                onClick = { onFilterChange(TrafficMapDetailFilter.NEW) },
            )
        }
    }
}

@Composable
private fun TrafficMapDetailControlRow(
    label: String,
    colors: TrafficMapColors,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(42.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            fontWeight = FontWeight.SemiBold,
            color = colors.inactiveText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun RowScope.TrafficMapDetailModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f)
                    } else {
                        Color.Transparent
                    },
                contentColor =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ),
        modifier = Modifier.weight(1f).height(30.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun trafficMapDetailCountryLabel(point: TrafficMapPoint): String {
    val baseLabel = "${countryEmoji(point.countryCode)} ${point.label}"
    val roleLabel =
        when (point.role) {
            TrafficMapPointRole.DESTINATION -> null
            TrafficMapPointRole.VPN_ROUTE -> stringResource(R.string.traffic_map_filter_vpn)
            TrafficMapPointRole.TOR_EXIT -> stringResource(R.string.traffic_map_filter_tor)
        }
    return roleLabel?.let { label -> "$baseLabel · $label" } ?: baseLabel
}

@Composable
private fun TrafficMapDetailHeaderRow(colors: TrafficMapColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrafficMapDetailTextCell(
            text = stringResource(R.string.traffic_map_country_header),
            modifier = Modifier.weight(0.54f),
            color = colors.inactiveText,
            textAlign = TextAlign.Start,
            strong = true,
        )
        TrafficMapDetailTextCell(
            text = stringResource(R.string.traffic_map_sessions_header),
            modifier = Modifier.weight(0.18f),
            color = colors.inactiveText,
            textAlign = TextAlign.End,
            strong = true,
        )
        TrafficMapDetailTextCell(
            text = stringResource(R.string.traffic_map_total_header),
            modifier = Modifier.weight(0.28f),
            color = colors.inactiveText,
            textAlign = TextAlign.End,
            strong = true,
        )
    }
}

@Composable
private fun TrafficMapDetailDestinationRow(
    country: String,
    sessions: Int,
    total: String,
    colors: TrafficMapColors,
    strong: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrafficMapDetailTextCell(
            text = country,
            modifier = Modifier.weight(0.54f),
            color = colors.legendText,
            textAlign = TextAlign.Start,
            strong = strong,
        )
        TrafficMapDetailTextCell(
            text = sessions.coerceAtLeast(0).toString(),
            modifier = Modifier.weight(0.18f),
            color = colors.legendText,
            textAlign = TextAlign.End,
            strong = strong,
        )
        TrafficMapDetailTextCell(
            text = total,
            modifier = Modifier.weight(0.28f),
            color = colors.legendText,
            textAlign = TextAlign.End,
            strong = strong,
        )
    }
}

@Composable
private fun TrafficMapDetailTextCell(
    text: String,
    modifier: Modifier,
    color: Color,
    textAlign: TextAlign,
    strong: Boolean,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 11.sp,
            lineHeight = 13.sp,
        ),
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium,
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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
        withFrameNanos { frameTimeNanos -> frameTimeNanos }
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
    val skeletonRow: @Composable (Boolean, Boolean) -> Unit = { showMetrics, dimmed ->
        val skeletonColor =
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (dimmed) 0.72f else 1f)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(if (showMetrics) 0.94f else 1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FoxholeSkeletonBlock(
                    modifier = Modifier.size(7.dp).clip(CircleShape),
                    color = skeletonColor,
                    shimmerProgress = shimmerProgress,
                )
                FoxholeSkeletonBlock(
                    modifier = Modifier.width(14.dp).height(10.dp),
                    color = skeletonColor,
                    shimmerProgress = shimmerProgress,
                )
                FoxholeSkeletonBlock(
                    modifier = Modifier.width(24.dp).height(10.dp),
                    color = skeletonColor,
                    shimmerProgress = shimmerProgress,
                )
            }
            if (showMetrics) {
                FoxholeSkeletonBlock(
                    modifier = Modifier.weight(0.48f).height(10.dp),
                    color = skeletonColor,
                    shimmerProgress = shimmerProgress,
                )
                FoxholeSkeletonBlock(
                    modifier = Modifier.weight(0.68f).height(10.dp),
                    color = skeletonColor,
                    shimmerProgress = shimmerProgress,
                )
            }
        }
    }
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        skeletonRow(false, false)
        skeletonRow(true, false)
        skeletonRow(true, true)
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
    val torRouteDashPhase =
        rememberTrafficMapTorRouteDashPhase(
            enabled = drawableEdges.any { edge -> edge.role == TrafficMapEdgeRole.TOR_ROUTE },
        )
    val originLat = state.originLat
    val originLon = state.originLon
    val originCountryCode = state.originCountryCode
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedMarkerKey by rememberSaveable { mutableStateOf<String?>(null) }
    val countryBitmap =
        rememberTrafficMapLandLayerBitmap(
            shapes = mapCountryShapes,
            canvasSize = canvasSize,
            color = colors.countryFill,
            boundaryColor = colors.countryBoundary,
        )
    val countryHighlightBitmap =
        rememberTrafficMapCountryHighlightLayerBitmap(
            shapes = mapCountryShapes,
            canvasSize = canvasSize,
            visuals = state.countryVisuals,
            colors = colors,
        )
    val mapContentDescription = remember(state) { trafficMapContentDescription(state) }
    val markerHitTargets =
        remember(canvasSize, state, drawableDestinations, drawableVpnRoute, drawableTorExit) {
            trafficMapMarkerHitTargets(
                state = state,
                destinations = drawableDestinations,
                vpnRoute = drawableVpnRoute,
                torExit = drawableTorExit,
                canvasSize = canvasSize,
            )
        }
    val selectedMarker = markerHitTargets.firstOrNull { target -> target.key == selectedMarkerKey }

    Box(
        modifier = modifier
            .onSizeChanged { size -> canvasSize = size }
            .semantics { contentDescription = mapContentDescription }
            .drawWithCache {
                val viewport = trafficMapViewport(size)
                val maxLineStroke = TRAFFIC_MAP_ROUTE_MAX_STROKE_DP.dp.toPx()
                val minLineStroke = TRAFFIC_MAP_ROUTE_MIN_STROKE_DP.dp.toPx()
                val routeHaloStrokeExtra = TRAFFIC_MAP_ROUTE_HALO_STROKE_EXTRA_DP.dp.toPx()
                val defaultDestinationRadius = 3.15.dp.toPx()
                val europeDestinationRadius = 2.35.dp.toPx()
                val routeNodeRadius = 2.75.dp.toPx()
                val torNodeRadius = 2.65.dp.toPx()
                val phoneWidth = 8.5.dp.toPx()
                val phoneHeight = 12.8.dp.toPx()
                val phoneCorner = CornerRadius(2.4.dp.toPx(), 2.4.dp.toPx())
                val phoneScreenInset = 1.4.dp.toPx()
                val phoneHomeRadius = 0.65.dp.toPx()
                val torRouteDash = TrafficMapTokens.TorRouteDashDp.dp.toPx()
                val torRouteGap = TrafficMapTokens.TorRouteGapDp.dp.toPx()
                val smallCountryCalloutRadius = TrafficMapTokens.SmallCountryCalloutRadiusDp.dp.toPx()
                val smallCountryCalloutStroke = TrafficMapTokens.SmallCountryCalloutStrokeDp.dp.toPx()
                val origin = project(originLat, originLon, viewport)
                val smallCountryCallouts =
                    trafficMapSmallCountryCallouts(
                        shapes = mapCountryShapes,
                        highlightedCountries = state.highlightedCountries,
                        viewport = viewport,
                    )
                val markerLayout =
                    resolveTrafficMapMarkerPlacements(
                        markers =
                            trafficMapMarkerProjections(
                                originCountryCode = originCountryCode,
                                origin = origin,
                                destinations = drawableDestinations,
                                vpnRoute = drawableVpnRoute,
                                torExit = drawableTorExit,
                                viewport = viewport,
                            ),
                        viewportTopLeft = viewport.topLeft,
                        viewportSize = viewport.size,
                    )
                val maxBytes =
                    (
                        drawableEdges.maxOfOrNull { edge -> edge.bytes }
                            ?: drawableDestinations.maxOfOrNull { destination -> destination.bytes }
                            ?: 1L
                    ).coerceAtLeast(1L)
                val routeDrawModels =
                    traceTrafficMapFrameSection("TrafficMap/buildRoutes") {
                        TrafficMapRouteModelCache.getOrBuild(
                            key =
                                TrafficMapRouteDrawCacheKey(
                                    edges = drawableEdges,
                                    originX = origin.x.roundToInt(),
                                    originY = origin.y.roundToInt(),
                                    viewportTopLeft =
                                        IntOffset(
                                            viewport.topLeft.x.roundToInt(),
                                            viewport.topLeft.y.roundToInt(),
                                        ),
                                    viewportSize =
                                        IntSize(
                                            viewport.size.width.roundToInt().coerceAtLeast(1),
                                            viewport.size.height.roundToInt().coerceAtLeast(1),
                                        ),
                                    maxBytes = maxBytes,
                                    minLineStrokeKey =
                                        (minLineStroke * TRAFFIC_MAP_ROUTE_STROKE_CACHE_SCALE)
                                            .roundToInt(),
                                    maxLineStrokeKey =
                                        (maxLineStroke * TRAFFIC_MAP_ROUTE_STROKE_CACHE_SCALE)
                                            .roundToInt(),
                                ),
                        ) {
                            val routeLanes = trafficRouteLanes(origin, drawableEdges, viewport)
                            if (drawableEdges.isNotEmpty()) {
                                drawableEdges
                                    .take(MAX_TRAFFIC_MAP_DRAW_EDGES)
                                    .map { edge ->
                                        val rawFrom = project(edge.fromLat, edge.fromLon, viewport)
                                        val rawTo = project(edge.toLat, edge.toLon, viewport)
                                        val from =
                                            markerLayout.edgeEndpointOffset(
                                                edge = edge,
                                                fromEndpoint = true,
                                                rawFrom = rawFrom,
                                                rawTo = rawTo,
                                            )
                                        val to =
                                            markerLayout.edgeEndpointOffset(
                                                edge = edge,
                                                fromEndpoint = false,
                                                rawFrom = rawFrom,
                                                rawTo = rawTo,
                                            )
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
                        }
                    }
                val destinationOffsets =
                    drawableDestinations
                        .take(MAX_TRAFFIC_MAP_DRAW_DESTINATIONS)
                        .mapIndexed { index, point ->
                            point to markerLayout.offsetForMarker(
                                key = point.destinationMarkerKey(index),
                                fallback = project(point.lat, point.lon, viewport),
                            )
                        }
                val vpnRouteOffset =
                    drawableVpnRoute?.let { point ->
                        point to markerLayout.offsetForMarker(
                            key = TRAFFIC_MAP_VPN_ROUTE_MARKER_KEY,
                            fallback = project(point.lat, point.lon, viewport),
                        )
                    }
                val torExitOffset =
                    drawableTorExit?.let { point ->
                        point to markerLayout.offsetForMarker(
                            key = TRAFFIC_MAP_TOR_EXIT_MARKER_KEY,
                            fallback = project(point.lat, point.lon, viewport),
                        )
                    }
                val originMarker =
                    originCountryCode?.let {
                        markerLayout.offsetForMarker(
                            key = TRAFFIC_MAP_ORIGIN_MARKER_KEY,
                            fallback = origin,
                        )
                    }

                onDrawBehind {
                    traceTrafficMapFrameSection("TrafficMap/draw") {
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
                    countryHighlightBitmap?.let { bitmap ->
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
                    smallCountryCallouts.forEach { callout ->
                        drawCircle(
                            color = colors.routeHalo.copy(alpha = 0.78f),
                            radius = smallCountryCalloutRadius + smallCountryCalloutStroke,
                            center = callout.offset,
                        )
                        drawCircle(
                            color = colors.countryDestinationHighlight.copy(alpha = 0.92f),
                            radius = smallCountryCalloutRadius,
                            center = callout.offset,
                            style = Stroke(width = smallCountryCalloutStroke),
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
                                    TrafficMapEdgeRole.VPN_ROUTE -> colors.vpnRoute
                                    TrafficMapEdgeRole.TOR_ROUTE -> colors.torExit
                                }.copy(alpha = route.alpha),
                            style =
                                Stroke(
                                    width = route.strokeWidth,
                                    cap = StrokeCap.Round,
                                    pathEffect =
                                        if (route.role == TrafficMapEdgeRole.TOR_ROUTE) {
                                            PathEffect.dashPathEffect(
                                                intervals = floatArrayOf(torRouteDash, torRouteGap),
                                                phase = torRouteDashPhase,
                                            )
                                        } else {
                                            null
                                        },
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
            }
            .fillMaxSize(),
    ) {
        TrafficMapMarkerSemanticsLayer(
            targets = markerHitTargets,
            onMarkerSelected = { target -> selectedMarkerKey = target.key },
        )
        selectedMarker?.let { target ->
            TrafficMapMarkerCallout(
                target = target,
                canvasSize = canvasSize,
            )
        }
    }
}

@Composable
private fun rememberTrafficMapTorRouteDashPhase(enabled: Boolean): Float {
    val phase by produceState(initialValue = 0f, enabled) {
        if (!enabled) {
            value = 0f
            return@produceState
        }
        while (true) {
            withFrameNanos { frameTimeNanos ->
                value =
                    (
                        (frameTimeNanos / TRAFFIC_MAP_TOR_ROUTE_DASH_FRAME_DIVISOR_NANOS) %
                            TRAFFIC_MAP_TOR_ROUTE_DASH_PHASE_STEPS
                        ).toFloat()
            }
        }
    }
    return phase
}

@Composable
private fun TrafficMapMarkerSemanticsLayer(
    targets: List<TrafficMapMarkerHitTarget>,
    onMarkerSelected: (TrafficMapMarkerHitTarget) -> Unit,
) {
    val density = LocalDensity.current
    val touchTargetSize = 44.dp
    val touchTargetSizePx = with(density) { touchTargetSize.toPx() }
    targets.forEach { target ->
        Box(
            modifier =
                Modifier
                    .offset {
                        IntOffset(
                            x = (target.offset.x - (touchTargetSizePx / 2f)).roundToInt(),
                            y = (target.offset.y - (touchTargetSizePx / 2f)).roundToInt(),
                        )
                    }
                    .size(touchTargetSize)
                    .clip(CircleShape)
                    .clickable { onMarkerSelected(target) }
                    .semantics { contentDescription = target.contentDescription }
                    .testTag("traffic_map_marker_${target.key.normalizedTrafficMapTestTag()}"),
        )
    }
}

@Composable
private fun TrafficMapMarkerCallout(
    target: TrafficMapMarkerHitTarget,
    canvasSize: IntSize,
) {
    val density = LocalDensity.current
    val calloutWidth = 132.dp
    val calloutOffset =
        remember(target.offset, canvasSize, density) {
            val widthPx = with(density) { calloutWidth.toPx() }
            val horizontalGap = with(density) { 8.dp.toPx() }
            val verticalLift = with(density) { 40.dp.toPx() }
            IntOffset(
                x = (target.offset.x + horizontalGap)
                    .coerceIn(0f, (canvasSize.width - widthPx).coerceAtLeast(0f))
                    .roundToInt(),
                y = (target.offset.y - verticalLift)
                    .coerceIn(0f, canvasSize.height.toFloat().coerceAtLeast(0f))
                    .roundToInt(),
            )
        }
    Surface(
        modifier =
            Modifier
                .offset { calloutOffset }
                .widthIn(min = 96.dp, max = calloutWidth)
                .semantics { contentDescription = target.contentDescription }
                .testTag("traffic_map_marker_callout"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = target.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = target.detail,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberTrafficMapLandLayerBitmap(
    shapes: List<TrafficMapCountryShape>,
    canvasSize: IntSize,
    color: Color,
    boundaryColor: Color,
): ImageBitmap? {
    val cachedBitmap =
        remember(shapes, canvasSize, color, boundaryColor) {
            TrafficMapLandLayerCache.currentBitmap(
                shapes = shapes,
                canvasSize = canvasSize,
                color = color,
                boundaryColor = boundaryColor,
            )
        }
    val bitmap by produceState(
        initialValue = cachedBitmap,
        shapes,
        canvasSize,
        color,
        boundaryColor,
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
                boundaryColor = boundaryColor,
            )
    }
    return bitmap
}

@Composable
private fun rememberTrafficMapCountryHighlightLayerBitmap(
    shapes: List<TrafficMapCountryShape>,
    canvasSize: IntSize,
    visuals: List<TrafficMapCountryVisual>,
    colors: TrafficMapColors,
): ImageBitmap? {
    val cachedBitmap =
        remember(shapes, canvasSize, visuals, colors) {
            TrafficMapCountryHighlightLayerCache.currentBitmap(
                shapes = shapes,
                canvasSize = canvasSize,
                visuals = visuals,
                colors = colors,
            )
        }
    val bitmap by produceState(
        initialValue = cachedBitmap,
        shapes,
        canvasSize,
        visuals,
        colors,
    ) {
        if (cachedBitmap != null) {
            value = cachedBitmap
        }
        if (
            trafficMapHighlightInputsEmpty(
                shapes = shapes,
                visuals = visuals,
                canvasSize = canvasSize,
            )
        ) {
            return@produceState
        }
        value =
            TrafficMapCountryHighlightLayerCache.bitmap(
                shapes = shapes,
                canvasSize = canvasSize,
                visuals = visuals,
                colors = colors,
            )
    }
    return bitmap
}

private fun trafficMapHighlightInputsEmpty(
    shapes: List<TrafficMapCountryShape>,
    visuals: List<TrafficMapCountryVisual>,
    canvasSize: IntSize,
): Boolean =
    shapes.isEmpty() ||
        visuals.isEmpty() ||
        canvasSize.width <= 0 ||
        canvasSize.height <= 0

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
    val shapes = traceTrafficMapSection("TrafficMap/loadShapes") {
        TrafficMapCountryShapeCache.load(appContext)
    }
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
            traceTrafficMapSection("TrafficMap/loadShapes") {
                withContext(TrafficMapRenderDispatcher.dispatcher) {
                    context.assets.open(TRAFFIC_MAP_COUNTRY_SHAPES_ASSET).use { inputStream ->
                        TrafficMapCountryShapeAssetParser().parse(inputStream)
                    }
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

internal enum class TrafficMapMarkerRole {
    ORIGIN,
    DESTINATION,
    VPN_ROUTE,
    TOR_EXIT,
}

internal data class TrafficMapMarkerProjection(
    val key: String,
    val countryCode: String?,
    val role: TrafficMapMarkerRole,
    val rawOffset: Offset,
)

internal data class TrafficMapMarkerPlacement(
    val key: String,
    val countryCode: String?,
    val role: TrafficMapMarkerRole,
    val rawOffset: Offset,
    val offset: Offset,
)

private data class TrafficMapMarkerHitTarget(
    val key: String,
    val label: String,
    val detail: String,
    val contentDescription: String,
    val offset: Offset,
)

private data class TrafficMapSmallCountryCallout(
    val countryCode: String,
    val offset: Offset,
)

internal enum class TrafficMapDetailSort {
    TOTAL,
    SESSIONS,
    COUNTRY,
}

internal enum class TrafficMapDetailRange(val limit: Int?) {
    TOP_10(10),
    TOP_30(30),
    ALL(null),
}

internal enum class TrafficMapDetailFilter {
    ALL,
    VPN,
    TOR,
    DIRECT,
    NEW,
}

internal fun trafficMapDetailPoints(
    destinations: List<TrafficMapPoint>,
    vpnRoute: TrafficMapPoint?,
    torExit: TrafficMapPoint?,
): List<TrafficMapPoint> {
    val routePoints = listOfNotNull(vpnRoute, torExit)
    return destinations + routePoints
}

internal fun trafficMapNewCountryCodes(countryVisuals: List<TrafficMapCountryVisual>): Set<String> =
    countryVisuals
        .filter(TrafficMapCountryVisual::isNewCountry)
        .mapTo(mutableSetOf(), TrafficMapCountryVisual::countryCode)

internal fun trafficMapDetailHiddenCountries(
    filteredCount: Int,
    visibleCount: Int,
    repositoryHiddenCount: Int,
    filter: TrafficMapDetailFilter,
): Int {
    val hiddenByRange = (filteredCount - visibleCount).coerceAtLeast(0)
    val hiddenByRepository =
        if (filter == TrafficMapDetailFilter.ALL) {
            repositoryHiddenCount
        } else {
            0
        }
    return (hiddenByRange + hiddenByRepository).coerceAtLeast(0)
}

internal fun trafficMapDetailDestinations(
    destinations: List<TrafficMapPoint>,
    sort: TrafficMapDetailSort,
    filter: TrafficMapDetailFilter,
    range: TrafficMapDetailRange,
    alreadySorted: Boolean = false,
    newCountryCodes: Set<String> = emptySet(),
): List<TrafficMapPoint> {
    val filtered =
        destinations.filter { point ->
            when (filter) {
                TrafficMapDetailFilter.ALL -> true
                TrafficMapDetailFilter.VPN -> point.role == TrafficMapPointRole.VPN_ROUTE
                TrafficMapDetailFilter.TOR -> point.role == TrafficMapPointRole.TOR_EXIT
                TrafficMapDetailFilter.DIRECT -> point.role == TrafficMapPointRole.DESTINATION
                TrafficMapDetailFilter.NEW -> point.countryCode in newCountryCodes
            }
        }
    val sorted =
        if (alreadySorted) {
            filtered
        } else {
            when (sort) {
                TrafficMapDetailSort.TOTAL ->
                    filtered.sortedWith(
                        compareByDescending<TrafficMapPoint> { point -> point.bytes }
                            .thenByDescending { point -> point.connections }
                            .thenBy { point -> point.label },
                    )
                TrafficMapDetailSort.SESSIONS ->
                    filtered.sortedWith(
                        compareByDescending<TrafficMapPoint> { point -> point.connections }
                            .thenByDescending { point -> point.bytes }
                            .thenBy { point -> point.label },
                    )
                TrafficMapDetailSort.COUNTRY ->
                    filtered.sortedWith(
                        compareBy<TrafficMapPoint> { point -> point.label }
                            .thenByDescending { point -> point.bytes },
                    )
            }
        }
    return range.limit?.let(sorted::take) ?: sorted
}

internal fun trafficMapDetailShowUnknownCountry(
    unknownCountryBytes: Long,
    unknownCountryConnections: Int,
    filter: TrafficMapDetailFilter,
): Boolean =
    when (filter) {
        TrafficMapDetailFilter.ALL -> unknownCountryBytes > 0L || unknownCountryConnections > 0
        TrafficMapDetailFilter.DIRECT -> unknownCountryBytes > 0L || unknownCountryConnections > 0
        TrafficMapDetailFilter.NEW,
        TrafficMapDetailFilter.TOR,
        TrafficMapDetailFilter.VPN,
        -> false
    }

internal data class DrawableTrafficMapEdge(
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val bytes: Long,
    val role: TrafficMapEdgeRole,
)

internal data class TrafficMapRouteDrawModel(
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

private fun trafficMapMarkerHitTargets(
    state: TrafficMapUiState,
    destinations: List<DrawableTrafficMapDestination>,
    vpnRoute: DrawableTrafficMapDestination?,
    torExit: DrawableTrafficMapDestination?,
    canvasSize: IntSize,
): List<TrafficMapMarkerHitTarget> {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) {
        return emptyList()
    }
    val viewport = trafficMapViewport(Size(width = canvasSize.width.toFloat(), height = canvasSize.height.toFloat()))
    val origin = project(state.originLat, state.originLon, viewport)
    val markerLayout =
        resolveTrafficMapMarkerPlacements(
            markers =
                trafficMapMarkerProjections(
                    originCountryCode = state.originCountryCode,
                    origin = origin,
                    destinations = destinations,
                    vpnRoute = vpnRoute,
                    torExit = torExit,
                    viewport = viewport,
                ),
            viewportTopLeft = viewport.topLeft,
            viewportSize = viewport.size,
        )
    return buildList {
        state.originCountryCode?.let {
            val label = state.originCountryName ?: state.originCountryCode ?: "Device"
            val detail = state.originCity?.takeIf(String::isNotBlank) ?: "Device"
            add(
                TrafficMapMarkerHitTarget(
                    key = TRAFFIC_MAP_ORIGIN_MARKER_KEY,
                    label = label,
                    detail = detail,
                    contentDescription = "Device $label $detail",
                    offset = markerLayout.offsetForMarker(TRAFFIC_MAP_ORIGIN_MARKER_KEY, origin),
                ),
            )
        }
        state.destinations
            .take(MAX_TRAFFIC_MAP_DRAW_DESTINATIONS)
            .forEachIndexed { index, point ->
                val key = trafficMapDestinationMarkerKey(index, point.countryCode.uppercase(Locale.US))
                val bytes = formatTrafficMapLegendBytes(point.bytes)
                add(
                    TrafficMapMarkerHitTarget(
                        key = key,
                        label = point.label,
                        detail = bytes,
                        contentDescription = "Destination ${point.label}, $bytes, ${point.connections} connections",
                        offset = markerLayout.offsetForMarker(key, project(point.lat, point.lon, viewport)),
                    ),
                )
            }
        state.vpnRoute?.let { point ->
            val bytes = formatTrafficMapLegendBytes(point.bytes)
            add(
                TrafficMapMarkerHitTarget(
                    key = TRAFFIC_MAP_VPN_ROUTE_MARKER_KEY,
                    label = "VPN ${point.label}",
                    detail = bytes,
                    contentDescription = "VPN route ${point.label}, $bytes",
                    offset =
                        markerLayout.offsetForMarker(
                            TRAFFIC_MAP_VPN_ROUTE_MARKER_KEY,
                            project(point.lat, point.lon, viewport),
                        ),
                ),
            )
        }
        state.torExit?.let { point ->
            val bytes = formatTrafficMapLegendBytes(point.bytes)
            add(
                TrafficMapMarkerHitTarget(
                    key = TRAFFIC_MAP_TOR_EXIT_MARKER_KEY,
                    label = "TOR ${point.label}",
                    detail = bytes,
                    contentDescription = "TOR exit ${point.label}, $bytes",
                    offset =
                        markerLayout.offsetForMarker(
                            TRAFFIC_MAP_TOR_EXIT_MARKER_KEY,
                            project(point.lat, point.lon, viewport),
                        ),
                ),
            )
        }
    }
}

private fun trafficMapSmallCountryCallouts(
    shapes: List<TrafficMapCountryShape>,
    highlightedCountries: Set<String>,
    viewport: TrafficMapViewport,
): List<TrafficMapSmallCountryCallout> {
    if (highlightedCountries.isEmpty()) {
        return emptyList()
    }
    val normalizedHighlightedCountries =
        highlightedCountries
            .map { countryCode -> countryCode.uppercase(Locale.US) }
            .toSet()
    return shapes
        .asSequence()
        .mapNotNull { shape ->
            val countryCode = shape.countryCode.uppercase(Locale.US)
            if (
                countryCode !in normalizedHighlightedCountries ||
                !shape.requiresProjectedCallout(
                    widthPx = viewport.size.width.toDouble(),
                    heightPx = viewport.size.height.toDouble(),
                    maxProjectedAreaPx = TrafficMapTokens.SmallCountryCalloutMaxAreaPx.toDouble(),
                )
            ) {
                null
            } else {
                val anchor = shape.projectedCalloutAnchor()
                TrafficMapSmallCountryCallout(
                    countryCode = countryCode,
                    offset = project(lat = anchor.lat, lon = anchor.lon, viewport = viewport),
                )
            }
        }
        .toList()
}

private fun trafficMapMarkerProjections(
    originCountryCode: String?,
    origin: Offset,
    destinations: List<DrawableTrafficMapDestination>,
    vpnRoute: DrawableTrafficMapDestination?,
    torExit: DrawableTrafficMapDestination?,
    viewport: TrafficMapViewport,
): List<TrafficMapMarkerProjection> =
    buildList {
        originCountryCode?.let { countryCode ->
            add(
                TrafficMapMarkerProjection(
                    key = TRAFFIC_MAP_ORIGIN_MARKER_KEY,
                    countryCode = countryCode.uppercase(Locale.US),
                    role = TrafficMapMarkerRole.ORIGIN,
                    rawOffset = origin,
                ),
            )
        }
        destinations
            .take(MAX_TRAFFIC_MAP_DRAW_DESTINATIONS)
            .forEachIndexed { index, destination ->
                add(
                    TrafficMapMarkerProjection(
                        key = destination.destinationMarkerKey(index),
                        countryCode = destination.countryCode,
                        role = TrafficMapMarkerRole.DESTINATION,
                        rawOffset = project(destination.lat, destination.lon, viewport),
                    ),
                )
            }
        vpnRoute?.let { point ->
            add(
                TrafficMapMarkerProjection(
                    key = TRAFFIC_MAP_VPN_ROUTE_MARKER_KEY,
                    countryCode = point.countryCode,
                    role = TrafficMapMarkerRole.VPN_ROUTE,
                    rawOffset = project(point.lat, point.lon, viewport),
                ),
            )
        }
        torExit?.let { point ->
            add(
                TrafficMapMarkerProjection(
                    key = TRAFFIC_MAP_TOR_EXIT_MARKER_KEY,
                    countryCode = point.countryCode,
                    role = TrafficMapMarkerRole.TOR_EXIT,
                    rawOffset = project(point.lat, point.lon, viewport),
                ),
            )
        }
    }

internal fun resolveTrafficMapMarkerPlacements(
    markers: List<TrafficMapMarkerProjection>,
    minDistancePx: Float = TrafficMapTokens.MarkerCollisionDistancePx,
    viewportTopLeft: Offset? = null,
    viewportSize: Size? = null,
): List<TrafficMapMarkerPlacement> {
    if (markers.size <= 1) {
        return markers.map { marker ->
            marker.toTrafficMapMarkerPlacement(
                offset = marker.rawOffset.clampedTrafficMapMarkerOffset(
                    viewportTopLeft = viewportTopLeft,
                    viewportSize = viewportSize,
                    inset = minDistancePx,
                ),
            )
        }
    }
    val resolved = mutableListOf<TrafficMapMarkerPlacement>()
    val placementsByOriginalIndex = arrayOfNulls<TrafficMapMarkerPlacement>(markers.size)
    markers
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<TrafficMapMarkerProjection>> { indexed -> indexed.value.role.markerPriority }
                .thenBy { indexed -> indexed.value.countryCode.orEmpty() }
                .thenBy { indexed -> indexed.index },
        )
        .forEach { indexed ->
            val marker = indexed.value
            val resolvedOffset =
                marker.resolvedTrafficMapMarkerOffset(
                    resolved = resolved,
                    minDistancePx = minDistancePx,
                    viewportTopLeft = viewportTopLeft,
                    viewportSize = viewportSize,
                )
            val placement = marker.toTrafficMapMarkerPlacement(offset = resolvedOffset)
            resolved += placement
            placementsByOriginalIndex[indexed.index] = placement
        }
    return placementsByOriginalIndex.filterNotNull()
}

private fun TrafficMapMarkerProjection.resolvedTrafficMapMarkerOffset(
    resolved: List<TrafficMapMarkerPlacement>,
    minDistancePx: Float,
    viewportTopLeft: Offset?,
    viewportSize: Size?,
): Offset {
    val clampedRaw =
        rawOffset.clampedTrafficMapMarkerOffset(
            viewportTopLeft = viewportTopLeft,
            viewportSize = viewportSize,
            inset = minDistancePx,
        )
    val collisionCandidate =
        if (!clampedRaw.collidesWithAnyTrafficMapMarker(resolved, minDistancePx)) {
            clampedRaw
        } else {
            (0 until TrafficMapTokens.MarkerCollisionMaxAttempts)
                .asSequence()
                .map { attempt ->
                    val angleDegrees = role.markerBaseAngleDegrees + (TrafficMapTokens.MarkerCollisionSweepDegrees * attempt)
                    val angleRadians = Math.toRadians(angleDegrees.toDouble())
                    val radius = minDistancePx * (1f + (attempt / TRAFFIC_MAP_MARKER_COLLISION_RING_SIZE))
                    Offset(
                        x = rawOffset.x + (cos(angleRadians) * radius).toFloat(),
                        y = rawOffset.y + (sin(angleRadians) * radius).toFloat(),
                    ).clampedTrafficMapMarkerOffset(
                        viewportTopLeft = viewportTopLeft,
                        viewportSize = viewportSize,
                        inset = minDistancePx,
                    )
                }
                .firstOrNull { candidate -> !candidate.collidesWithAnyTrafficMapMarker(resolved, minDistancePx) }
                ?: clampedRaw
        }
    return collisionCandidate
}

private fun TrafficMapMarkerProjection.toTrafficMapMarkerPlacement(offset: Offset): TrafficMapMarkerPlacement =
    TrafficMapMarkerPlacement(
        key = key,
        countryCode = countryCode,
        role = role,
        rawOffset = rawOffset,
        offset = offset,
    )

private fun Offset.collidesWithAnyTrafficMapMarker(
    resolved: List<TrafficMapMarkerPlacement>,
    minDistancePx: Float,
): Boolean =
    resolved.any { placement ->
        distanceTo(placement.offset) < minDistancePx
    }

private fun Offset.clampedTrafficMapMarkerOffset(
    viewportTopLeft: Offset?,
    viewportSize: Size?,
    inset: Float,
): Offset {
    val clamped =
        if (viewportTopLeft == null || viewportSize == null) {
            this
        } else {
            val minX = viewportTopLeft.x + inset
            val maxX = viewportTopLeft.x + viewportSize.width - inset
            val minY = viewportTopLeft.y + inset
            val maxY = viewportTopLeft.y + viewportSize.height - inset
            if (maxX < minX || maxY < minY) {
                this
            } else {
                Offset(
                    x = x.coerceIn(minX, maxX),
                    y = y.coerceIn(minY, maxY),
                )
            }
        }
    return clamped
}

private fun List<TrafficMapMarkerPlacement>.offsetForMarker(
    key: String,
    fallback: Offset,
): Offset =
    firstOrNull { placement -> placement.key == key }?.offset ?: fallback

private fun List<TrafficMapMarkerPlacement>.edgeEndpointOffset(
    edge: DrawableTrafficMapEdge,
    fromEndpoint: Boolean,
    rawFrom: Offset,
    rawTo: Offset,
): Offset {
    val rawOffset = if (fromEndpoint) rawFrom else rawTo
    val matches = markerPlacementsAt(rawOffset)
    if (matches.isEmpty()) {
        return rawOffset
    }
    val oppositeMatches = markerPlacementsAt(if (fromEndpoint) rawTo else rawFrom)
    val preferredRoles =
        if (fromEndpoint) {
            edge.fromEndpointPreferredMarkerRoles(oppositeMatches)
        } else {
            edge.toEndpointPreferredMarkerRoles(oppositeMatches)
        }
    return preferredRoles
        .firstNotNullOfOrNull { role -> matches.firstOrNull { placement -> placement.role == role } }
        ?.offset
        ?: matches.first().offset
}

private fun List<TrafficMapMarkerPlacement>.markerPlacementsAt(rawOffset: Offset): List<TrafficMapMarkerPlacement> =
    filter { placement -> placement.rawOffset.distanceTo(rawOffset) <= TRAFFIC_MAP_MARKER_RAW_MATCH_TOLERANCE_PX }

private fun DrawableTrafficMapEdge.fromEndpointPreferredMarkerRoles(
    oppositeMatches: List<TrafficMapMarkerPlacement>,
): List<TrafficMapMarkerRole> =
    when (role) {
        TrafficMapEdgeRole.DIRECT -> listOf(TrafficMapMarkerRole.ORIGIN)
        TrafficMapEdgeRole.VPN_ROUTE ->
            if (oppositeMatches.any { placement -> placement.role == TrafficMapMarkerRole.VPN_ROUTE }) {
                listOf(TrafficMapMarkerRole.ORIGIN)
            } else {
                listOf(TrafficMapMarkerRole.VPN_ROUTE, TrafficMapMarkerRole.ORIGIN)
            }
        TrafficMapEdgeRole.TOR_ROUTE ->
            if (oppositeMatches.any { placement -> placement.role == TrafficMapMarkerRole.TOR_EXIT }) {
                listOf(TrafficMapMarkerRole.VPN_ROUTE, TrafficMapMarkerRole.ORIGIN)
            } else {
                listOf(TrafficMapMarkerRole.TOR_EXIT, TrafficMapMarkerRole.VPN_ROUTE)
            }
    }

private fun DrawableTrafficMapEdge.toEndpointPreferredMarkerRoles(
    oppositeMatches: List<TrafficMapMarkerPlacement>,
): List<TrafficMapMarkerRole> =
    when (role) {
        TrafficMapEdgeRole.DIRECT -> listOf(TrafficMapMarkerRole.DESTINATION)
        TrafficMapEdgeRole.VPN_ROUTE ->
            if (oppositeMatches.any { placement -> placement.role == TrafficMapMarkerRole.ORIGIN }) {
                listOf(TrafficMapMarkerRole.VPN_ROUTE)
            } else {
                listOf(TrafficMapMarkerRole.DESTINATION)
            }
        TrafficMapEdgeRole.TOR_ROUTE ->
            if (
                oppositeMatches.any { placement ->
                    placement.role == TrafficMapMarkerRole.VPN_ROUTE || placement.role == TrafficMapMarkerRole.ORIGIN
                }
            ) {
                listOf(TrafficMapMarkerRole.TOR_EXIT)
            } else {
                listOf(TrafficMapMarkerRole.DESTINATION)
            }
    }

private fun DrawableTrafficMapDestination.destinationMarkerKey(index: Int): String =
    trafficMapDestinationMarkerKey(index = index, countryCode = countryCode)

private fun trafficMapDestinationMarkerKey(
    index: Int,
    countryCode: String,
): String =
    "destination:$index:$countryCode"

private fun String.normalizedTrafficMapTestTag(): String =
    lowercase(Locale.US).replace(Regex("[^a-z0-9_]+"), "_").trim('_')

private val TrafficMapMarkerRole.markerPriority: Int
    get() =
        when (this) {
            TrafficMapMarkerRole.ORIGIN -> 0
            TrafficMapMarkerRole.VPN_ROUTE -> 1
            TrafficMapMarkerRole.TOR_EXIT -> 2
            TrafficMapMarkerRole.DESTINATION -> 3
        }

private val TrafficMapMarkerRole.markerBaseAngleDegrees: Float
    get() =
        when (this) {
            TrafficMapMarkerRole.ORIGIN -> -90f
            TrafficMapMarkerRole.VPN_ROUTE -> -18f
            TrafficMapMarkerRole.TOR_EXIT -> 128f
            TrafficMapMarkerRole.DESTINATION -> 42f
        }

private fun Offset.distanceTo(other: Offset): Float {
    val dx = x - other.x
    val dy = y - other.y
    return sqrt((dx * dx) + (dy * dy))
}

@Composable
private fun TrafficMapLegend(
    state: TrafficMapUiState,
    modifier: Modifier = Modifier,
) {
    val colors = trafficMapColors()
    val destinations = remember(state.destinations) { state.destinations.take(TRAFFIC_MAP_DASHBOARD_TOP_COUNTRIES) }
    val routePoints = remember(state.vpnRoute, state.torExit) { listOfNotNull(state.vpnRoute, state.torExit) }
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (destinations.isEmpty() && routePoints.isEmpty()) {
            TrafficMapEmptySummary(
                state = state,
                colors = colors,
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(
                modifier =
                    Modifier
                        .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TrafficMapLegendLabel(
                    text = stringResource(R.string.traffic_map_route_header),
                    color = colors.inactiveText,
                )
                Text(
                    text = state.trafficMapRouteChain(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = colors.legendText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (destinations.isNotEmpty()) {
                    TrafficMapLegendLabel(
                        text = stringResource(R.string.traffic_map_top_countries_header),
                        color = colors.inactiveText,
                    )
                    destinations.forEach { point ->
                        TrafficMapLegendDestinationRow(
                            point = point,
                            markerColor = colors.destination,
                            textColor = colors.legendText,
                        )
                    }
                }
                if (state.unknownCountryBytes > 0L) {
                    TrafficMapLegendSummaryRow(
                        label = stringResource(R.string.traffic_map_unknown_country),
                        value = formatTrafficMapLegendBytes(state.unknownCountryBytes),
                        color = colors.inactiveText,
                    )
                }
                if (state.hiddenCountryCount > 0) {
                    Text(
                        text = stringResource(R.string.traffic_map_more_countries, state.hiddenCountryCount),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                        ),
                        color = colors.inactiveText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.totalBytes > 0L) {
                    TrafficMapLegendSummaryRow(
                        label = stringResource(R.string.traffic_map_total_header),
                        value =
                            stringResource(
                                R.string.traffic_map_total_summary,
                                state.countryCount,
                                formatTrafficMapLegendBytes(state.totalBytes),
                            ),
                        color = colors.legendText,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficMapEmptySummary(
    state: TrafficMapUiState,
    colors: TrafficMapColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
                fontSize = 10.sp,
                lineHeight = 12.sp,
            ),
            fontWeight = FontWeight.SemiBold,
            color = colors.inactiveText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.traffic_map_waiting_connections_helper),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                lineHeight = 11.sp,
            ),
            color = colors.inactiveText,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TrafficMapLegendLabel(
    text: String,
    color: Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 8.sp,
            lineHeight = 9.sp,
        ),
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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

private fun Int.signFloat(): Float = if (this < 0) -1f else 1f

private fun TrafficMapUiState.originLocationLabel(): String =
    listOfNotNull(
        originCountryName?.let { countryName -> "${countryEmoji(originCountryCode)} $countryName" }
            ?: originCountryCode?.let(::countryEmoji),
        originCity?.takeIf(String::isNotBlank),
        )
        .joinToString(separator = "\n")
        .ifBlank { "IP" }

private fun TrafficMapUiState.trafficMapRouteChain(): String {
    val origin =
        listOfNotNull(
            originCountryCode?.let(::countryEmoji),
            originCountryName ?: originCountryCode,
        )
            .joinToString(separator = " ")
            .ifBlank { "Device" }
    val routeNodes =
        listOfNotNull(
            vpnRoute?.let { point -> "${countryEmoji(point.countryCode)} VPN" },
            torExit?.let { point -> "${countryEmoji(point.countryCode)} TOR" },
        )
    return (listOf(origin) + routeNodes).joinToString(separator = " -> ")
}

private fun trafficMapContentDescription(state: TrafficMapUiState): String {
    val routeParts =
        buildList {
            add("Device ${state.originCountryName ?: state.originCountryCode ?: "unknown"}")
            state.vpnRoute?.let { point -> add("VPN ${point.label}") }
            state.torExit?.let { point -> add("TOR ${point.label}") }
        }.joinToString(separator = ". ")
    val topDestination =
        state.destinations.firstOrNull()?.let { point ->
            "Top destination ${point.label} ${formatTrafficMapLegendBytes(point.bytes)}."
        }.orEmpty()
    val countrySummary =
        if (state.countryCount > 0) {
            "${state.countryCount} countries total."
        } else {
            "No active destinations."
        }
    return "Traffic map. $routeParts. $topDestination $countrySummary"
}

@Composable
private fun TrafficMapLegendDestinationRow(
    point: TrafficMapPoint,
    markerColor: Color,
    textColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
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
            Text(
                text = countryEmoji(point.countryCode),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                ),
                maxLines = 1,
            )
            Text(
                text = point.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                ),
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TrafficMapLegendCell(
            text = formatTrafficMapLegendBytes(point.bytes),
            modifier = Modifier.widthIn(min = 42.dp, max = 64.dp),
            textAlign = TextAlign.End,
            color = textColor,
        )
    }
}

@Composable
private fun TrafficMapLegendSummaryRow(
    label: String,
    value: String,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                lineHeight = 10.sp,
            ),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                lineHeight = 10.sp,
            ),
            fontWeight = FontWeight.SemiBold,
            color = color,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
            addPath(androidTrafficMapCountryPath(shape = shape, viewport = viewport))
        }
    }

private fun androidTrafficMapCountryPath(
    shape: TrafficMapCountryShape,
    viewport: TrafficMapViewport,
): AndroidPath =
    AndroidPath().apply {
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

private fun trafficMapLandBitmap(
    size: Size,
    shapes: List<TrafficMapCountryShape>,
    viewport: TrafficMapViewport,
    color: Color,
    boundaryColor: Color,
): ImageBitmap {
    val width = size.width.toInt().coerceAtLeast(1)
    val height = size.height.toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val landPath = androidTrafficMapLandPath(shapes, viewport)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawPath(
        landPath,
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.FILL
            this.color = color.toArgb()
        },
    )
    canvas.drawPath(
        landPath,
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeWidth = 0.65f
            this.color = boundaryColor.toArgb()
        },
    )
    return bitmap.asImageBitmap()
}

private fun trafficMapCountryHighlightBitmap(
    size: Size,
    shapes: List<TrafficMapCountryShape>,
    viewport: TrafficMapViewport,
    visuals: List<TrafficMapCountryVisual>,
    colors: TrafficMapColors,
): ImageBitmap {
    val width = size.width.toInt().coerceAtLeast(1)
    val height = size.height.toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val shapesByCountryCode = shapes.associateBy { shape -> shape.countryCode.uppercase(Locale.US) }
    visuals.dominantTrafficMapCountryVisuals().forEach { visual ->
        val shape = shapesByCountryCode[visual.countryCode.uppercase(Locale.US)] ?: return@forEach
        val countryPath = androidTrafficMapCountryPath(shape = shape, viewport = viewport)
        canvas.drawPath(
            countryPath,
            AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                color = visual.trafficMapHighlightColor(colors).toArgb()
            },
        )
        if (visual.isNewCountry || visual.isRouteNode) {
            canvas.drawPath(
                countryPath,
                AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                    style = AndroidPaint.Style.STROKE
                    strokeWidth = if (visual.isRouteNode) 1.35f else 1.05f
                    color = visual.trafficMapHighlightStrokeColor(colors).toArgb()
                },
            )
        }
    }
    return bitmap.asImageBitmap()
}

private object TrafficMapCountryHighlightLayerCache {
    private const val MAX_ENTRIES = 8
    private const val SIZE_BUCKET_PX = 32
    private val lock = Any()
    private val bitmaps =
        LinkedHashMap<TrafficMapCountryHighlightLayerKey, ImageBitmap>(
            MAX_ENTRIES,
            0.75f,
            true,
        )

    fun currentBitmap(
        shapes: List<TrafficMapCountryShape>,
        canvasSize: IntSize,
        visuals: List<TrafficMapCountryVisual>,
        colors: TrafficMapColors,
    ): ImageBitmap? {
        val key =
            highlightLayerKey(
                shapes = shapes,
                canvasSize = canvasSize,
                visuals = visuals,
                colors = colors,
            )
        synchronized(lock) {
            return bitmaps[key]
        }
    }

    suspend fun bitmap(
        shapes: List<TrafficMapCountryShape>,
        canvasSize: IntSize,
        visuals: List<TrafficMapCountryVisual>,
        colors: TrafficMapColors,
    ): ImageBitmap =
        withContext(TrafficMapRenderDispatcher.dispatcher) {
            val key =
                highlightLayerKey(
                    shapes = shapes,
                    canvasSize = canvasSize,
                    visuals = visuals,
                    colors = colors,
                )
            synchronized(lock) {
                bitmaps[key]?.let { bitmap -> return@withContext bitmap }
            }
            val size = Size(key.width.toFloat(), key.height.toFloat())
            val bitmap =
                traceTrafficMapSection("TrafficMap/renderHighlightBitmap") {
                    trafficMapCountryHighlightBitmap(
                        size = size,
                        shapes = shapes,
                        viewport = TrafficMapViewport(topLeft = Offset.Zero, size = size),
                        visuals = visuals,
                        colors = colors,
                    )
                }
            synchronized(lock) {
                bitmaps[key] = bitmap
                while (bitmaps.size > MAX_ENTRIES) {
                    val eldest = bitmaps.entries.firstOrNull()?.key ?: break
                    bitmaps.remove(eldest)
                }
            }
            bitmap
        }

    private fun highlightLayerKey(
        shapes: List<TrafficMapCountryShape>,
        canvasSize: IntSize,
        visuals: List<TrafficMapCountryVisual>,
        colors: TrafficMapColors,
    ): TrafficMapCountryHighlightLayerKey {
        val bitmapSize = trafficMapLandLayerBitmapSize(canvasSize)
        return TrafficMapCountryHighlightLayerKey(
            width = bucketDimension(bitmapSize.width),
            height = bucketDimension(bitmapSize.height),
            shapesIdentity = System.identityHashCode(shapes),
            shapeCount = shapes.size,
            visualsHash = visuals.dominantTrafficMapCountryVisuals().hashCode(),
            destinationColor = colors.countryDestinationHighlight.toArgb(),
            originColor = colors.countryOriginHighlight.toArgb(),
            vpnColor = colors.vpnRoute.toArgb(),
            torColor = colors.torExit.toArgb(),
        )
    }

    private fun bucketDimension(value: Int): Int =
        (((value.coerceAtLeast(1) + SIZE_BUCKET_PX - 1) / SIZE_BUCKET_PX) * SIZE_BUCKET_PX)
}

private object TrafficMapLandLayerCache {
    private const val MAX_ENTRIES = 8
    private const val SIZE_BUCKET_PX = 32
    private val lock = Any()
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
        boundaryColor: Color,
    ): ImageBitmap? {
        val key =
            landLayerKey(
                shapes = shapes,
                canvasSize = canvasSize,
                color = color,
                boundaryColor = boundaryColor,
            )
        synchronized(lock) {
            return bitmaps[key]
        }
    }

    suspend fun bitmap(
        shapes: List<TrafficMapCountryShape>,
        canvasSize: IntSize,
        color: Color,
        boundaryColor: Color,
    ): ImageBitmap =
        withContext(TrafficMapRenderDispatcher.dispatcher) {
            val startedAtMs = SystemClock.elapsedRealtime()
            val key =
                landLayerKey(
                    shapes = shapes,
                    canvasSize = canvasSize,
                    color = color,
                    boundaryColor = boundaryColor,
                )
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
                    traceTrafficMapSection("TrafficMap/renderLandBitmap") {
                        trafficMapLandBitmap(
                            size = size,
                            shapes = shapes,
                            viewport = TrafficMapViewport(topLeft = Offset.Zero, size = size),
                            color = color,
                            boundaryColor = boundaryColor,
                        )
                    }
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
        boundaryColor: Color,
    ): TrafficMapLandLayerKey {
        val bitmapSize = trafficMapLandLayerBitmapSize(canvasSize)
        return TrafficMapLandLayerKey(
            width = bucketDimension(bitmapSize.width),
            height = bucketDimension(bitmapSize.height),
            color = color.toArgb(),
            boundaryColor = boundaryColor.toArgb(),
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
                boundaryColor = TRAFFIC_MAP_DEFAULT_COUNTRY_BOUNDARY,
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
                    boundaryColor = TRAFFIC_MAP_DEFAULT_COUNTRY_BOUNDARY,
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
    val boundaryColor: Int,
    val shapesIdentity: Int,
    val shapeCount: Int,
)

private data class TrafficMapCountryHighlightLayerKey(
    val width: Int,
    val height: Int,
    val shapesIdentity: Int,
    val shapeCount: Int,
    val visualsHash: Int,
    val destinationColor: Int,
    val originColor: Int,
    val vpnColor: Int,
    val torColor: Int,
)

private fun List<TrafficMapCountryVisual>.dominantTrafficMapCountryVisuals(): List<TrafficMapCountryVisual> =
    groupBy { visual -> visual.countryCode.uppercase(Locale.US) }
        .map { (_, visuals) ->
            visuals.maxWith(
                compareBy<TrafficMapCountryVisual> { visual -> visual.role.trafficMapRolePriority() }
                    .thenBy { visual -> visual.intensity }
                    .thenBy { visual -> visual.bytes },
            )
        }
        .sortedWith(
            compareByDescending<TrafficMapCountryVisual> { visual -> visual.role.trafficMapRolePriority() }
                .thenByDescending { visual -> visual.intensity }
                .thenBy { visual -> visual.countryCode },
        )

private fun CountryTrafficRole.trafficMapRolePriority(): Int =
    when (this) {
        CountryTrafficRole.TOR_EXIT -> 4
        CountryTrafficRole.VPN_ROUTE -> 3
        CountryTrafficRole.DESTINATION -> 2
        CountryTrafficRole.ORIGIN -> 1
    }

private fun TrafficMapCountryVisual.trafficMapHighlightColor(colors: TrafficMapColors): Color {
    val alpha =
        when (role) {
            CountryTrafficRole.ORIGIN -> 0.18f + (0.16f * intensity)
            CountryTrafficRole.DESTINATION -> 0.18f + (0.30f * intensity)
            CountryTrafficRole.VPN_ROUTE -> 0.32f + (0.23f * intensity)
            CountryTrafficRole.TOR_EXIT -> 0.32f + (0.23f * intensity)
        }.coerceIn(0.12f, 0.56f)
    return when (role) {
        CountryTrafficRole.ORIGIN -> colors.countryOriginHighlight
        CountryTrafficRole.DESTINATION -> colors.countryDestinationHighlight
        CountryTrafficRole.VPN_ROUTE -> colors.vpnRoute
        CountryTrafficRole.TOR_EXIT -> colors.torExit
    }.copy(alpha = alpha)
}

private fun TrafficMapCountryVisual.trafficMapHighlightStrokeColor(colors: TrafficMapColors): Color =
    when (role) {
        CountryTrafficRole.ORIGIN -> colors.origin
        CountryTrafficRole.DESTINATION -> colors.countryDestinationHighlight
        CountryTrafficRole.VPN_ROUTE -> colors.vpnRoute
        CountryTrafficRole.TOR_EXIT -> colors.torExit
    }.copy(alpha = if (isNewCountry) 0.78f else 0.62f)

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
    val countryBoundary: Color,
    val countryDestinationHighlight: Color,
    val countryOriginHighlight: Color,
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
        countryBoundary = onSurfaceVariantColor.copy(alpha = if (darkTheme) 0.18f else 0.24f),
        countryDestinationHighlight = accentColor,
        countryOriginHighlight = accentColor.copy(alpha = if (darkTheme) 0.36f else 0.30f),
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

private val TRAFFIC_MAP_CARD_TOTAL_HEIGHT = 216.dp
private val TRAFFIC_MAP_DEFAULT_COUNTRY_FILL = Color(0xFF3E3F41)
private val TRAFFIC_MAP_DEFAULT_COUNTRY_BOUNDARY = Color(0xFF5B5D61)
private const val TRAFFIC_MAP_ROUTE_MIN_STROKE_DP = 0.9f
private const val TRAFFIC_MAP_ROUTE_MAX_STROKE_DP = 2.2f
private const val TRAFFIC_MAP_ROUTE_STROKE_CACHE_SCALE = 1_000f
private const val TRAFFIC_MAP_ROUTE_MIN_ALPHA = 0.42f
private const val TRAFFIC_MAP_ROUTE_ALPHA_RANGE = 0.36f
private const val TRAFFIC_MAP_ROUTE_HALO_STROKE_EXTRA_DP = 1.4f
private const val TRAFFIC_MAP_ROUTE_HALO_ALPHA_MULTIPLIER = 0.22f
private const val TRAFFIC_MAP_MARKER_COLLISION_RING_SIZE = 6f
private const val TRAFFIC_MAP_MARKER_RAW_MATCH_TOLERANCE_PX = 0.5f
private const val TRAFFIC_MAP_ORIGIN_MARKER_KEY = "origin"
private const val TRAFFIC_MAP_VPN_ROUTE_MARKER_KEY = "route:vpn"
private const val TRAFFIC_MAP_TOR_EXIT_MARKER_KEY = "route:tor"
private const val TRAFFIC_MAP_TOR_ROUTE_DASH_FRAME_DIVISOR_NANOS = 16_666_667L
private const val TRAFFIC_MAP_TOR_ROUTE_DASH_PHASE_STEPS = 11L
private const val TRAFFIC_MAP_HEAVY_CONTENT_SETTLE_DELAY_MS = 0L
private const val TRAFFIC_MAP_POWER_STATE_STARTUP_DELAY_MS = 0L
private const val TRAFFIC_MAP_WEIGHT = 0.62f
private const val TRAFFIC_MAP_LEGEND_WEIGHT = 0.38f
private const val TRAFFIC_MAP_WORLD_ASPECT_RATIO = 2f
private const val TRAFFIC_MAP_MIN_LAT = -55.0
private const val TRAFFIC_MAP_MAX_LAT = 85.0
private const val TRAFFIC_MAP_LAT_RANGE = TRAFFIC_MAP_MAX_LAT - TRAFFIC_MAP_MIN_LAT
private const val MAX_TRAFFIC_MAP_DRAW_EDGES = 30
private const val MAX_TRAFFIC_MAP_DRAW_DESTINATIONS = 30
private const val TRAFFIC_MAP_DASHBOARD_TOP_COUNTRIES = 3
private const val TRAFFIC_MAP_LOW_BATTERY_PERCENT = 10
private const val TRAFFIC_MAP_PREWARM_COMPACT_WIDTH_FRACTION = 0.58f
private const val TRAFFIC_MAP_PREWARM_PRIMARY_WIDTH_FRACTION = 0.65f
private const val TRAFFIC_MAP_PREWARM_WIDE_WIDTH_FRACTION = 0.74f
private const val TRAFFIC_ROUTE_PI = 3.141592653589793
private const val TRAFFIC_ROUTE_ANGLE_BUCKET_RADIANS = 0.17453292519943295
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

private inline fun <T> traceTrafficMapFrameSection(
    name: String,
    block: () -> T,
): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

private suspend fun <T> traceTrafficMapSection(
    name: String,
    block: suspend () -> T,
): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
