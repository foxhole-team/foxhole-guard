package com.foxhole.guard.ui.cli.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.I2pTrafficBucket
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.StatisticsWindow
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.VpnMode
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

/**
 * Slices [samples] into [bucketCount] equal buckets of the window ending at [nowMs], each split by
 * network type.
 *
 * The split comes from WHAT WAS RECORDED, not from what the settings say now. [deviceWindows] carry
 * the one thing the per-app rows do not — which route was up that minute — so each bucket's measured
 * volume is divided by the shares those windows report. Deriving the Tor lane from the current
 * settings instead (which is what this did) meant history changed shape whenever the user changed
 * mode: switch Tor off and yesterday's Tor traffic silently became VPN traffic. A chart of the past
 * must not move when the present does.
 *
 * [i2pBuckets] are the I2P lane's own hourly rows. FoxCore also attributes those same client-flow
 * bytes to the originating apps, so the independent I2P measurement is carved out of the aggregate
 * before it is put on the VPN/Tor/firewall lanes. Transit relay bytes stay out of this graph: they
 * are router traffic rather than the user's traffic and have their own I2P detail row. A bucket
 * whose device windows were never recorded keeps the remaining volume on the VPN lane — "not
 * recorded" must not become a claim about a route.
 *
 * Buckets with no samples are honest zeros.
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
    // Bucketed by the hour's own start, exactly as the per-app rows are bucketed by their sample
    // start: an hour lands in the slice it began in.
    val i2p = i2pBuckets.filter { bucket -> bucket.hourStartMs >= startMs }.groupBy { bucket ->
        slot(bucket.hourStartMs)
    }
    return List(bucketCount) { index ->
        val appTotalBytes = grouped[index].orEmpty().sumOf { sample -> sample.totalBytes.coerceAtLeast(0L) }
        val deviceBucket = devices[index].orEmpty()
        // The graph is device traffic first. Per-app windows need Android Usage Access and can be
        // legitimately empty even while the VPN moves bytes; the persisted device window is the
        // privacy-safe aggregate fallback that keeps the VPN/Tor/firewall chart alive.
        val deviceTotalBytes = deviceBucket.sumOf { window -> window.totalBytes.coerceAtLeast(0L) }
        val observedBytes = appTotalBytes.takeIf { it > 0L } ?: deviceTotalBytes
        val i2pBytes = i2p[index].orEmpty().sumOf { bucket -> bucket.totals.ownBytes.coerceAtLeast(0L) }
        // FoxCore attributes I2P flows to their originating app as well as to the dedicated I2P
        // cumulative counter. Therefore the aggregate/app total already contains own I2P bytes:
        // putting the whole total on VPN/Tor and then adding I2P made the graph count those bytes
        // twice. Carve the independently measured I2P lane out first; transit relay bytes remain
        // excluded because they are not user traffic and are shown in the separate I2P panel.
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
        )
    }
}

/**
 * How this slice's measured device traffic divides between the routes that were actually up.
 *
 * Zero shares without mode-tagged windows: the runtime records them behind their own statistics
 * switches, and "not recorded" must not become a claim that traffic went through Tor or the
 * firewall. What is left over is the VPN lane, which is also where an untagged slice lands whole.
 */
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
    // TOR is an overlay, not a mutually exclusive replacement for the Android VPN hop. A positive
    // profile id means the same bytes traversed the selected VPN and Tor; TOR_ONLY and the local
    // guard use negative sentinels and therefore do not invent a VPN series.
    val vpn = shareOf { window ->
        val profileId = window.profileId?.toLongOrNull()
        profileId?.let { it > 0L }
            ?: (window.vpnMode != VpnMode.TOR && window.profileId != LOCAL_GUARD_PROFILE_TAG)
    }
    // The local guard alone — no tunnel profile — so those bytes were never VPN traffic. Counted
    // after Tor so a Tor-over-guard minute is not claimed twice.
    val firewall = shareOf { window ->
        window.vpnMode != VpnMode.TOR && window.profileId == LOCAL_GUARD_PROFILE_TAG
    }
    return CliRecordedRouteShares(vpn = vpn, tor = tor, firewall = firewall)
}

/** Bucket count per window, one per unit the label promises: 24 hours, 7 days, 30 days. */
internal fun cliStatsSparklineBucketCount(window: StatisticsWindow): Int = when (window) {
    StatisticsWindow.DAY -> 24
    StatisticsWindow.WEEK -> 7
    StatisticsWindow.MONTH -> 30
}

/**
 * Which of the four traffic lanes this chart speaks for. Traffic splits a column into the exact
 * visible set, and the legend names only routes that are live/configured now or moved bytes inside
 * the selected historical window. That keeps history after a module switches off without reserving
 * phantom columns for routes that were never active.
 */
@Immutable
internal data class CliStatsChartLanes(
    val vpn: Boolean = false,
    val tor: Boolean = false,
    val i2p: Boolean = false,
    val firewall: Boolean = false,
)

/** Drawing-order indexes for the lanes that really have a column in every time slice. */
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

/**
 * A stable 1/2/5 scale. Tiny sample changes no longer resize every bar on every statistics tick;
 * the ceiling moves only after crossing a readable boundary.
 */
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

/**
 * The pixel traffic chart of the overview: one column per time slice, and each column split into
 * one bar for every visible lane — VPN plus the enabled or historically populated modules — drawn
 * side by side out of hard pixel cells.
 *
 * Separate bars rather than one stack, because a stack answers "how much altogether" and hides the
 * one question this chart exists for: how much went through each route. Side by side each visible
 * lane has its own height. The scale is therefore the tallest single visible lane in the window,
 * not the tallest sum.
 *
 * Deliberately static — no reveal or crossfade. The chart re-renders on every statistics tick, and
 * any entry animation keyed on the data restarted right there, reading as a permanent flicker that
 * never finished filling.
 *
 * The empty window is still a chart: grid, baseline and `0` labels stay, so "no traffic" and
 * "no chart" cannot be confused.
 */
@Composable
internal fun CliStatsSparkline(
    buckets: List<CliStatsTrafficBucket>,
    lanes: CliStatsChartLanes,
    window: StatisticsWindow,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val visibleLanes = lanes.visibleLaneIndexes()
    // Only displayed lanes participate in the scale. A hidden zero lane must not keep a phantom
    // quarter-width slot, and a historical lane is already made visible by the caller.
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
        Column(modifier = Modifier.weight(1f)) {
            CliStatsSparklineCanvas(
                buckets = buckets,
                scaleMax = scaleMax,
                visibleLanes = visibleLanes,
            )
            CliStatsSparklineAxis(columns = buckets.size, numbered = window != StatisticsWindow.MONTH)
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
        // The scale column matches the canvas height exactly, so max/mid/zero sit on their lines.
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
 * The counted scale under the chart: 24…1 for a day, 7…1 for a week.
 *
 * One number per column, counting down to the present, because that is what the columns are — the
 * previous "-24h … 24x1h … now" said the same thing three times and located nothing: with it you
 * could see a spike but not which hour it was.
 *
 * Every column keeps its slot even when its label is dropped, so the numbers stay over their own
 * bars. Labels thin out by a whole factor (every 2nd, every 3rd…) rather than by pixels, and the
 * two ends are always printed: a scale whose first or last tick is missing is not a scale.
 *
 * The month is [numbered] `false`: thirty columns fit at most a handful of numbers, so the scale
 * printed a few scattered day counts and left the rest blank — a ruler with four marks on it. A
 * dash under every column is the honest version of the same row: it shows where the columns are
 * without pretending to locate them.
 */
@Composable
private fun CliStatsSparklineAxis(
    columns: Int,
    numbered: Boolean,
) {
    val colors = LocalCliColors.current
    if (columns <= 0) return
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val perLabel = AXIS_LABEL_WIDTH + CliSpacing.xs
        val fits = (maxWidth / perLabel).toInt().coerceAtLeast(2)
        val stride = ((columns + fits - 1) / fits).coerceAtLeast(1)
        Row(modifier = Modifier.fillMaxWidth()) {
            repeat(columns) { index ->
                // Counting down to now: the last column is 1.
                val number = columns - index
                val shown = index == 0 || number == 1 || index % stride == 0
                Text(
                    text = when {
                        !numbered -> AXIS_TICK
                        shown -> number.toString()
                        else -> ""
                    },
                    style = CliType.small,
                    color = colors.faint,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CliStatsSparklineCanvas(
    buckets: List<CliStatsTrafficBucket>,
    scaleMax: Long,
    visibleLanes: IntArray,
) {
    val colors = LocalCliColors.current
    Canvas(modifier = Modifier.fillMaxWidth().height(SPARKLINE_HEIGHT)) {
        if (buckets.isEmpty()) return@Canvas
        val gap = 1.dp.toPx()
        val cell = SPARKLINE_CELL.toPx()
        val step = cell + gap
        val slotWidth = ((size.width - gap * (buckets.size - 1)) / buckets.size).coerceAtLeast(1f)
        // Grid and baseline are meaningful even before any route exists; bars are not.
        drawDottedLine(y = 0f, color = colors.border)
        drawDottedLine(y = size.height / 2f, color = colors.border)
        drawRect(
            color = colors.border,
            topLeft = Offset(0f, size.height - 1f),
            size = Size(size.width, 1f),
        )
        if (scaleMax <= 0L || visibleLanes.isEmpty()) return@Canvas
        // Exactly the active/history-bearing bars share the slice. No invisible module reserves an
        // empty internal column; at least one pixel remains for each visible lane.
        val laneCount = visibleLanes.size
        val barWidth = ((slotWidth - gap * (laneCount - 1)) / laneCount).coerceAtLeast(1f)
        val totalCells = (size.height / step).toInt().coerceAtLeast(1)
        val laneColors = listOf(colors.vpn, colors.tor, colors.i2p, colors.firewall)
        buckets.forEachIndexed { index, bucket ->
            val slotX = index * (slotWidth + gap)
            val bytesByLane = bucket.laneBytes()
            visibleLanes.forEachIndexed { visibleIndex, lane ->
                val cells = laneCells(bytesByLane[lane], scaleMax, totalCells)
                val x = slotX + visibleIndex * (barWidth + gap)
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

/** The bucket's four lanes in drawing order: VPN, TOR, I2P, firewall. */
private fun CliStatsTrafficBucket.laneBytes(): LongArray =
    longArrayOf(vpnBytes, torBytes, i2pBytes, firewallBytes)

/**
 * Lane bytes → whole pixel cells. Any nonzero lane keeps at least one cell: the bars are
 * independent now, so a small one can no longer be squeezed out by a large neighbour, and a lane
 * that carried traffic must not read as a lane that carried none.
 */
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDottedLine(
    y: Float,
    color: Color,
) {
    val dash = 2.dp.toPx()
    val period = 5.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawRect(color = color, topLeft = Offset(x, y), size = Size(dash, 1f))
        x += period
    }
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

private val SPARKLINE_HEIGHT = 56.dp
private val SPARKLINE_CELL = 3.dp
private val SPARKLINE_SCALE_WIDTH = 58.dp

// The widest number the scale prints is two digits; below this the labels collide and thin out.
private val AXIS_LABEL_WIDTH = 14.dp

// The unnumbered tick: one dash per column. Not localized — it is a mark, not a word.
private const val AXIS_TICK = "-"

// TrafficWindow persists the profile id as text; the local guard is the "no tunnel profile" one.
private val LOCAL_GUARD_PROFILE_TAG = LOCAL_GUARD_PROFILE_ID.toString()
