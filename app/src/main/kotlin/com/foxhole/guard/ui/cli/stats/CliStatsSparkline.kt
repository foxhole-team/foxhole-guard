package com.foxhole.guard.ui.cli.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.I2pTrafficBucket
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.StatisticsWindow
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.VisualStyle
import com.foxhole.core.model.VpnMode
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliScaledDp
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Lanes are split by what the device windows recorded, not by current settings, so history cannot change shape when the mode does.
 * I2P is carved out of the aggregate first because FoxCore also attributes those bytes to the originating apps; transit relay bytes stay out.
 */
internal fun cliStatsTrafficBuckets(
    samples: List<AppTrafficWindow>,
    windowMs: Long,
    nowMs: Long,
    bucketCount: Int,
    deviceWindows: List<TrafficWindow> = emptyList(),
    i2pBuckets: List<I2pTrafficBucket> = emptyList(),
): List<CliStatsTrafficBucket> {
    require(bucketCount > 0) { "bucketCount must be positive" }
    val bucketMs = (windowMs / bucketCount).coerceAtLeast(1L)
    val startMs = nowMs - windowMs
    val slot = { timestampMs: Long ->
        (((timestampMs - startMs) / bucketMs).toInt()).coerceIn(0, bucketCount - 1)
    }
    val grouped = samples.filter { sample -> sample.startedAtMs >= startMs }.groupBy { sample ->
        slot(sample.startedAtMs)
    }
    val devices = deviceWindows.filter { window -> window.startedAtMs >= startMs }.groupBy { window ->
        slot(window.startedAtMs)
    }
    val i2p = i2pBuckets.filter { bucket -> bucket.hourStartMs >= startMs }.groupBy { bucket ->
        slot(bucket.hourStartMs)
    }
    return List(bucketCount) { index ->
        val appTotalBytes = grouped[index].orEmpty().sumOf { sample -> sample.totalBytes.coerceAtLeast(0L) }
        val deviceBucket = devices[index].orEmpty()
        val sampled = grouped[index] != null || devices[index] != null || i2p[index] != null
        val deviceTotalBytes = deviceBucket.sumOf { window -> window.totalBytes.coerceAtLeast(0L) }
        val observedBytes = appTotalBytes.takeIf { it > 0L } ?: deviceTotalBytes
        val i2pBytes = i2p[index].orEmpty().sumOf { bucket -> bucket.totals.ownBytes.coerceAtLeast(0L) }
        val routedBytes = (observedBytes - i2pBytes).coerceAtLeast(0L)
        val shares = recordedRouteShares(deviceBucket)
        val vpnBytes = (routedBytes * shares.vpn).toLong()
        val torBytes = (routedBytes * shares.tor).toLong()
        val firewallBytes = (routedBytes * shares.firewall).toLong()
        CliStatsTrafficBucket(
            vpnBytes = vpnBytes,
            torBytes = torBytes,
            i2pBytes = i2pBytes,
            firewallBytes = firewallBytes,
            observedBytes = observedBytes.coerceAtLeast(i2pBytes),
            sampled = sampled,
        )
    }
}

private class CliRecordedRouteShares(
    val vpn: Double,
    val tor: Double,
    val firewall: Double,
)

private fun recordedRouteShares(windows: List<TrafficWindow>): CliRecordedRouteShares {
    val total = windows.sumOf { window -> window.totalBytes.coerceAtLeast(0L) }
    if (total <= 0L) {
        return CliRecordedRouteShares(vpn = 1.0, tor = 0.0, firewall = 0.0)
    }
    fun shareOf(predicate: (TrafficWindow) -> Boolean): Double =
        windows
            .filter(predicate)
            .sumOf { window -> window.totalBytes.coerceAtLeast(0L) }
            .toDouble()
            .div(total.toDouble())
            .coerceIn(0.0, 1.0)
    val tor = shareOf { window -> window.vpnMode == VpnMode.TOR }
    val vpn = shareOf { window ->
        val profileId = window.profileId?.toLongOrNull()
        profileId?.let { it > 0L }
            ?: (window.vpnMode != VpnMode.TOR && window.profileId != LOCAL_GUARD_PROFILE_TAG)
    }
    val firewall = shareOf { window ->
        window.vpnMode != VpnMode.TOR && window.profileId == LOCAL_GUARD_PROFILE_TAG
    }
    return CliRecordedRouteShares(vpn = vpn, tor = tor, firewall = firewall)
}

internal fun cliStatsSparklineBucketCount(window: StatisticsWindow): Int = when (window) {
    StatisticsWindow.DAY -> 24
    StatisticsWindow.WEEK -> 7
    StatisticsWindow.MONTH -> 30
}

@Immutable
internal data class CliStatsAxisTick(val bucketIndex: Int, val label: String)

/**
 * The chart's time axis. Buckets are one real unit wide - an hour for the day window, a day for
 * the others - and the window ends on the next such boundary rather than on "now", so every
 * bucket edge is a clock boundary and the ticks land on round times instead of drifting with
 * the current minute. Labels are formatted from each bucket's own start instant, so they stay
 * truthful across a DST shift even though the buckets themselves are fixed-length.
 */
@Immutable
internal data class CliStatsChartAxis(
    val endMs: Long,
    val bucketMs: Long,
    val bucketCount: Int,
    val ticks: List<CliStatsAxisTick>,
    val labelledBucketIndexes: Set<Int>,
) {
    val windowMs: Long get() = bucketMs * bucketCount
    val startMs: Long get() = endMs - windowMs
}

internal fun cliStatsChartAxis(
    window: StatisticsWindow,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): CliStatsChartAxis {
    val bucketCount = cliStatsSparklineBucketCount(window)
    val hourly = window == StatisticsWindow.DAY
    val bucketMs = if (hourly) HOUR_BUCKET_MS else DAY_BUCKET_MS
    val unit = if (hourly) ChronoUnit.HOURS else ChronoUnit.DAYS
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val truncated = now.truncatedTo(unit)
    val end = if (truncated.toInstant().toEpochMilli() == nowMs) truncated else truncated.plus(1, unit)
    val endMs = end.toInstant().toEpochMilli()
    val startMs = endMs - bucketMs * bucketCount
    val ticks = (0 until bucketCount)
        .map { index ->
            CliStatsAxisTick(
                bucketIndex = index,
                label = cliStatsRelativeAxisLabel(window = window, index = index),
            )
        }
    return CliStatsChartAxis(
        endMs = endMs,
        bucketMs = bucketMs,
        bucketCount = bucketCount,
        ticks = ticks,
        labelledBucketIndexes = cliStatsAxisLabelledBucketIndexes(window),
    )
}

internal fun cliStatsRelativeAxisLabel(window: StatisticsWindow, index: Int): String = when (window) {
    StatisticsWindow.DAY -> (24 - index).toString()
    StatisticsWindow.WEEK -> (index + 1).toString()
    StatisticsWindow.MONTH -> (30 - index).toString()
}

internal fun cliStatsAxisLabelledBucketIndexes(window: StatisticsWindow): Set<Int> = when (window) {
    StatisticsWindow.DAY -> setOf(0, 4, 8, 12, 16, 20, 23)
    StatisticsWindow.WEEK -> (0 until 7).toSet()
    StatisticsWindow.MONTH -> setOf(0, 5, 10, 15, 20, 25, 29)
}

internal fun cliStatsLabelledBucketIndexes(
    bucketCount: Int,
    slotsPerLabel: Int,
): Set<Int> {
    if (bucketCount <= 0) return emptySet()
    val stride = slotsPerLabel.coerceIn(1, bucketCount)
    return (0 until bucketCount).filterTo(mutableSetOf()) { index ->
        (bucketCount - 1 - index) % stride == 0
    }
}

internal fun cliStatsSlotWidthPx(
    widthPx: Float,
    bucketCount: Int,
    gapPx: Float,
): Float {
    if (bucketCount <= 0) return 0f
    return ((widthPx - gapPx * (bucketCount - 1)) / bucketCount).coerceAtLeast(1f)
}

internal fun cliStatsSlotLeftPx(
    index: Int,
    widthPx: Float,
    bucketCount: Int,
    gapPx: Float,
): Float = index * (cliStatsSlotWidthPx(widthPx, bucketCount, gapPx) + gapPx)

internal fun cliStatsSlotCenterPx(
    index: Int,
    widthPx: Float,
    bucketCount: Int,
    gapPx: Float,
): Float =
    cliStatsSlotLeftPx(index, widthPx, bucketCount, gapPx) +
        cliStatsSlotWidthPx(widthPx, bucketCount, gapPx) / 2f

private const val HOUR_BUCKET_MS = 3_600_000L
private const val DAY_BUCKET_MS = 86_400_000L

@Immutable
internal data class CliStatsChartLanes(
    val vpn: Boolean = false,
    val tor: Boolean = false,
    val i2p: Boolean = false,
    val firewall: Boolean = false,
)

internal fun CliStatsChartLanes.visibleLaneIndexes(): IntArray {
    val result = IntArray(
        (if (vpn) 1 else 0) + (if (tor) 1 else 0) + (if (i2p) 1 else 0) + (if (firewall) 1 else 0),
    )
    var index = 0
    if (vpn) result[index++] = VPN_LANE
    if (tor) result[index++] = TOR_LANE
    if (i2p) result[index++] = I2P_LANE
    if (firewall) result[index] = FIREWALL_LANE
    return result
}

internal fun cliStatsScaleCeiling(value: Long): Long {
    if (value <= 0L) return 0L
    var magnitude = 1L
    while (magnitude <= Long.MAX_VALUE / 10L) {
        val five = if (magnitude > Long.MAX_VALUE / 5L) Long.MAX_VALUE else magnitude * 5L
        if (value <= five) break
        magnitude *= 10L
    }
    if (value <= magnitude) return magnitude
    if (magnitude <= Long.MAX_VALUE / 2L && value <= magnitude * 2L) return magnitude * 2L
    if (magnitude <= Long.MAX_VALUE / 5L && value <= magnitude * 5L) return magnitude * 5L
    return if (magnitude <= Long.MAX_VALUE / 10L) magnitude * 10L else Long.MAX_VALUE
}

@Composable
internal fun CliStatsSparkline(
    buckets: List<CliStatsTrafficBucket>,
    lanes: CliStatsChartLanes,
    axis: CliStatsChartAxis,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    var chartEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { chartEntered = true }
    val reveal by animateFloatAsState(
        targetValue = if (chartEntered) 1f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "statsChartReveal",
    )
    val visibleLanes = lanes.visibleLaneIndexes()
    val maxLane = if (visibleLanes.isEmpty()) {
        0L
    } else {
        buckets.maxOfOrNull { bucket ->
            val bytes = bucket.laneBytes()
            visibleLanes.maxOf { lane -> bytes[lane] }
        } ?: 0L
    }
    val scaleMax = cliStatsScaleCeiling(maxLane)
    Row(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    alpha = reveal
                    scaleY = 0.92f + (0.08f * reveal)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                },
        ) {
            CliStatsSparklineChart(
                buckets = buckets,
                scaleMax = scaleMax,
                visibleLanes = visibleLanes,
                axis = axis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (lanes.vpn) {
                    CliStatsLegendEntry(label = "vpn", color = colors.vpn)
                }
                if (lanes.tor) {
                    CliStatsLegendEntry(label = "tor", color = colors.tor)
                }
                if (lanes.i2p) {
                    CliStatsLegendEntry(label = "i2p", color = colors.i2p)
                }
                if (lanes.firewall) {
                    CliStatsLegendEntry(label = "fw", color = colors.firewall)
                }
            }
        }
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Column(
            modifier = Modifier.width(SPARKLINE_SCALE_WIDTH).height(SPARKLINE_HEIGHT),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = if (scaleMax > 0L) CliFormat.bytes(scaleMax) else "",
                style = CliType.small,
                color = colors.dim,
            )
            Text(
                text = if (scaleMax > 0L) CliFormat.bytes(scaleMax / 2L) else "",
                style = CliType.small,
                color = colors.faint,
            )
            Text(text = "0", style = CliType.small, color = colors.faint)
        }
    }
}

/**
 * Canvas and axis row under one width measurement.
 *
 * They have to agree on two things — the slot pitch and which boundaries are named — so both are
 * derived once here and handed down, rather than each half computing its own from its own
 * constraints.
 */
@Composable
private fun CliStatsSparklineChart(
    buckets: List<CliStatsTrafficBucket>,
    scaleMax: Long,
    visibleLanes: IntArray,
    axis: CliStatsChartAxis,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { SPARKLINE_GAP.toPx() }
    Column {
        CliStatsSparklineCanvas(
            buckets = buckets,
            bucketCount = axis.bucketCount,
            scaleMax = scaleMax,
            visibleLanes = visibleLanes,
            gridlineBuckets = axis.labelledBucketIndexes,
        )
        CliStatsSparklineAxis(
            axis = axis,
            labelled = axis.labelledBucketIndexes,
            gapPx = gapPx,
        )
    }
}

@Composable
private fun CliStatsSparklineAxis(
    axis: CliStatsChartAxis,
    labelled: Set<Int>,
    gapPx: Float,
) {
    val colors = LocalCliColors.current
    if (axis.bucketCount <= 0 || axis.ticks.isEmpty()) return
    Box(modifier = Modifier.fillMaxWidth()) {
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                axis.ticks.forEach { tick ->
                    val printed = tick.bucketIndex in labelled
                    Text(
                        text = if (printed) tick.label else AXIS_DOT,
                        style = CliType.small,
                        color = if (printed) colors.faint else colors.border,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        textAlign = TextAlign.Center,
                    )
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { measurable ->
                measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
            }
            val height = placeables.maxOfOrNull(Placeable::height) ?: 0
            val rowWidth = constraints.maxWidth
            layout(rowWidth, height) {
                placeables.forEachIndexed { index, placeable ->
                    val center = cliStatsSlotCenterPx(index, rowWidth.toFloat(), axis.bucketCount, gapPx)
                    val x = (center - placeable.width / 2f)
                        .coerceIn(0f, (rowWidth - placeable.width).coerceAtLeast(0).toFloat())
                    placeable.placeRelative(x.roundToInt(), 0)
                }
            }
        }
    }
}

@Composable
private fun CliStatsSparklineCanvas(
    buckets: List<CliStatsTrafficBucket>,
    bucketCount: Int,
    scaleMax: Long,
    visibleLanes: IntArray,
    gridlineBuckets: Set<Int>,
) {
    val colors = LocalCliColors.current
    val plain = LocalCliVisualStyle.current == VisualStyle.PLAIN
    Canvas(modifier = Modifier.fillMaxWidth().height(SPARKLINE_HEIGHT)) {
        if (buckets.isEmpty() || bucketCount <= 0) return@Canvas
        val gap = SPARKLINE_GAP.toPx()
        val cell = SPARKLINE_CELL.toPx()
        val step = cell + gap
        val slotWidth = cliStatsSlotWidthPx(size.width, bucketCount, gap)
        gridlineBuckets.forEach { index ->
            if (index in buckets.indices) {
                drawRect(
                    color = colors.border.copy(alpha = GRIDLINE_ALPHA),
                    topLeft = Offset(cliStatsSlotCenterPx(index, size.width, bucketCount, gap), 0f),
                    size = Size(1f, size.height),
                )
            }
        }
        buckets.forEachIndexed { index, bucket ->
            drawRect(
                color = if (bucket.sampled) colors.border else colors.border.copy(alpha = GAP_BASELINE_ALPHA),
                topLeft = Offset(cliStatsSlotLeftPx(index, size.width, bucketCount, gap), size.height - 1f),
                size = Size(slotWidth + gap, 1f),
            )
        }
        if (scaleMax <= 0L || visibleLanes.isEmpty()) return@Canvas
        val laneCount = visibleLanes.size
        val barWidth = ((slotWidth - gap * (laneCount - 1)) / laneCount).coerceAtLeast(1f)
        val totalCells = (size.height / step).toInt().coerceAtLeast(1)
        val laneColors = listOf(colors.vpn, colors.tor, colors.i2p, colors.firewall)
        buckets.forEachIndexed { index, bucket ->
            if (!bucket.sampled) return@forEachIndexed
            val slotX = cliStatsSlotLeftPx(index, size.width, bucketCount, gap)
            val bytesByLane = bucket.laneBytes()
            visibleLanes.forEachIndexed { visibleIndex, lane ->
                val x = slotX + visibleIndex * (barWidth + gap)
                if (plain) {
                    drawPlainBar(
                        x = x,
                        width = barWidth,
                        bytes = bytesByLane[lane],
                        maxLane = scaleMax,
                        color = laneColors[lane],
                    )
                } else {
                    val cells = laneCells(bytesByLane[lane], scaleMax, totalCells)
                    repeat(cells) { row ->
                        drawCell(
                            x = x,
                            row = row,
                            width = barWidth,
                            cell = cell,
                            step = step,
                            color = laneColors[lane],
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlainBar(
    x: Float,
    width: Float,
    bytes: Long,
    maxLane: Long,
    color: Color,
) {
    if (bytes <= 0L) return
    val barHeight = (bytes.toDouble() / maxLane.toDouble() * size.height).toFloat()
        .coerceIn(2f, size.height)
    drawRoundRect(
        color = color,
        topLeft = Offset(x, size.height - barHeight),
        size = Size(width, barHeight),
        cornerRadius = CornerRadius(width / 3f, width / 3f),
    )
}

private fun CliStatsTrafficBucket.laneBytes(): LongArray =
    longArrayOf(vpnBytes, torBytes, i2pBytes, firewallBytes)

private fun laneCells(
    bytes: Long,
    maxLane: Long,
    totalCells: Int,
): Int {
    if (bytes <= 0L) return 0
    val cells = (bytes.toDouble() / maxLane.toDouble() * totalCells).toInt()
    return cells.coerceIn(1, totalCells)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCell(
    x: Float,
    row: Int,
    width: Float,
    cell: Float,
    step: Float,
    color: Color,
) {
    drawRect(
        color = color,
        topLeft = Offset(x, size.height - (row * step) - cell),
        size = Size(width, cell),
    )
}

@Composable
private fun CliStatsLegendEntry(
    label: String,
    color: Color,
) {
    val colors = LocalCliColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).background(color))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = CliType.small, color = colors.dim)
    }
}

private const val VPN_LANE = 0
private const val TOR_LANE = 1
private const val I2P_LANE = 2
private const val FIREWALL_LANE = 3

private const val GRIDLINE_ALPHA = 0.35f
private const val GAP_BASELINE_ALPHA = 0.25f

private val SPARKLINE_HEIGHT = 56.dp
private val SPARKLINE_CELL = 3.dp
private val SPARKLINE_GAP = 1.dp
private val SPARKLINE_SCALE_WIDTH = cliScaledDp(58f)

private const val AXIS_DOT = "."

private val LOCAL_GUARD_PROFILE_TAG = LOCAL_GUARD_PROFILE_ID.toString()
