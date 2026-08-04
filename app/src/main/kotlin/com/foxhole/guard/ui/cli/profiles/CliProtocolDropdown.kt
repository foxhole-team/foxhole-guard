package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfilesRouteUiState
import com.foxhole.guard.ui.cli.CliColors
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.smartProfileConnectDurations
import com.foxhole.guard.ui.smartProfileDownOptionIds
import com.foxhole.guard.ui.smartProfileMetricsUpdatedAt
import com.foxhole.guard.ui.smartProfileServerPings
import com.foxhole.guard.ui.smartStartRememberedLatency

/**
 * The available-protocols TABLE of a multi-protocol VPN profile — five columns
 * `marker name ★ | T | P | L | S`: T/P/L are the connect/ping/latency metrics, S is the state glyph
 * in the bracket alphabet the whole UI speaks (`[x]` on, `[ ]` off, `[~]` testing, `[!]` down, `[?]`
 * never measured). Column widths come from a [TextMeasurer] pass over the longest cell of THIS
 * profile, because the rows are independent siblings inside a LazyColumn — intrinsics or weights
 * would only align a single subtree, and LanaPixel is proportional so spaces cannot align anything.
 *
 * Two hosts use it. The home quick-selector shows it inline (tap a row applies that protocol) and
 * leaves [onOptionEnabledToggle] null, so the S cell is drawn but inert. The profiles-screen
 * management sheet passes the callback, which turns the S cell into the per-protocol on/off switch
 * (N1). Manual TEST is never part of the table — it lives on the profiles screen only.
 *
 * Picking a row calls [onOptionSelected] after the switch is requested — the host uses that to close
 * itself so any resulting switch confirm (B3/P2) is visible anchored to the bottom of the screen.
 * A disabled option cannot be picked until it is toggled back on.
 */
@Composable
internal fun CliProtocolDropdown(
    viewModel: HomeViewModel,
    state: ProfilesRouteUiState,
    profile: Profile,
    onOptionSelected: () -> Unit,
    modifier: Modifier = Modifier,
    onOptionEnabledToggle: ((optionId: String, enabled: Boolean) -> Unit)? = null,
) {
    val colors = LocalCliColors.current
    // The table adapts to host width: the name column is elastic, T/P/L/S are measured from
    // content, and on a narrow screen (~336dp inside the sheet at 360dp) the inter-column gaps
    // shrink instead.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cellSpacing = if (maxWidth < CliProtocolCompactHostWidth) 2.dp else CliSpacing.xs
        val widths = rememberCliProtocolColumnWidths(state, profile, hostWidth = maxWidth)
        Column(modifier = Modifier.fillMaxWidth()) {
            CliProtocolTableHeader(widths = widths, cellSpacing = cellSpacing)
            profile.protocolOptions.forEach { option ->
                CliProtocolOptionRow(
                    presentation = protocolRowPresentation(state, profile, option),
                    widths = widths,
                    cellSpacing = cellSpacing,
                    onClick = {
                        if (option.enabled) {
                            viewModel.onSelectProfileProtocolOption(profile.id, option.id)
                            onOptionSelected()
                        }
                    },
                    onToggleEnabled = onOptionEnabledToggle?.let { toggle ->
                        {
                            toggle(option.id, !option.enabled)
                        }
                    },
                )
            }
            CliElbowLine(text = stringResource(R.string.cli_prof_test_legend), color = colors.faint)
        }
    }
}

/** Caption line of the table: the name column floats, T/P/L/S sit in the measured slots. */
@Composable
private fun CliProtocolTableHeader(
    widths: CliProtocolColumnWidths,
    cellSpacing: Dp,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = CliSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        CliProtocolTableCell(
            text = CLI_PROTO_HEADER_CONNECT,
            cellWidth = widths.connect,
            color = colors.faint,
            spacing = cellSpacing,
        )
        CliProtocolTableCell(
            text = CLI_PROTO_HEADER_PING,
            cellWidth = widths.ping,
            color = colors.faint,
            spacing = cellSpacing,
        )
        CliProtocolTableCell(
            text = CLI_PROTO_HEADER_LATENCY,
            cellWidth = widths.latency,
            color = colors.faint,
            spacing = cellSpacing,
        )
        Spacer(modifier = Modifier.width(cellSpacing))
        Box(modifier = Modifier.width(widths.status), contentAlignment = Alignment.Center) {
            Text(text = CLI_PROTO_HEADER_STATUS, style = CliType.small, color = colors.faint, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CliProtocolOptionRow(
    presentation: CliProtocolRowPresentation,
    widths: CliProtocolColumnWidths,
    cellSpacing: Dp,
    onClick: () -> Unit,
    onToggleEnabled: (() -> Unit)?,
) {
    val colors = LocalCliColors.current
    val metricColor = if (presentation.status == CliProtocolStatus.DOWN) colors.err else colors.faint
    // Column S is tappable only where the host passed onToggleEnabled. In the home quick
    // selector a tap goes to the row — it selects the protocol rather than silently toggling.
    val statusModifier = if (onToggleEnabled == null) {
        Modifier
    } else {
        Modifier.cliPressable(onClick = onToggleEnabled)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = presentation.marker, style = CliType.body, color = presentation.markerColor(colors))
        // Stars measure first, so a long subscription name ellipsizes while the rank stays.
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = presentation.name,
                style = CliType.body,
                color = presentation.nameColor(colors),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (presentation.stars.isNotEmpty()) {
                Text(text = " ${presentation.stars}", style = CliType.body, color = colors.accent)
            }
        }
        CliProtocolTableCell(
            text = presentation.connect,
            cellWidth = widths.connect,
            color = metricColor,
            spacing = cellSpacing,
        )
        CliProtocolTableCell(
            text = presentation.ping,
            cellWidth = widths.ping,
            color = metricColor,
            spacing = cellSpacing,
        )
        CliProtocolTableCell(
            text = presentation.latency,
            cellWidth = widths.latency,
            color = metricColor,
            spacing = cellSpacing,
        )
        Spacer(modifier = Modifier.width(cellSpacing))
        // Column S carries the CliToggleRow bracket glyph: state, and in the sheet the toggle.
        Box(
            modifier = Modifier
                .width(widths.status)
                .defaultMinSize(minHeight = 48.dp)
                .then(statusModifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = presentation.status.glyph,
                style = CliType.body,
                color = presentation.status.color(colors),
                maxLines = 1,
            )
        }
    }
}

/** Gap plus a fixed measured cell, so columns do not drift between rows. */
@Composable
private fun CliProtocolTableCell(
    text: String,
    cellWidth: Dp,
    color: Color,
    spacing: Dp,
) {
    Spacer(modifier = Modifier.width(spacing))
    Text(
        text = text,
        style = CliType.small,
        color = color,
        maxLines = 1,
        modifier = Modifier.width(cellWidth),
    )
}

/**
 * Protocol state in column S. Priority is fixed by declaration order and resolved in
 * [cliProtocolStatus]: a running probe outranks "down", "down" outranks disabled, and only
 * then come activity and the presence of measurements. [glyph] is the same bracket alphabet as
 * `CliToggleRow` и `profileSelectionMarker`.
 */
internal enum class CliProtocolStatus(val glyph: String) {
    TESTING("[~]"),
    DOWN("[!]"),
    OFF("[ ]"),
    ACTIVE("[x]"),
    UNTESTED("[?]"),
    READY("[x]"),
}

/**
 * Pure state mapper: [measured] means the protocol has any probe record at all, [active]
 * that the smart profile picked it as the working one.
 */
internal fun cliProtocolStatus(
    testing: Boolean,
    down: Boolean,
    enabled: Boolean,
    active: Boolean,
    measured: Boolean,
): CliProtocolStatus = when {
    testing -> CliProtocolStatus.TESTING
    down -> CliProtocolStatus.DOWN
    !enabled -> CliProtocolStatus.OFF
    active -> CliProtocolStatus.ACTIVE
    !measured -> CliProtocolStatus.UNTESTED
    else -> CliProtocolStatus.READY
}

/**
 * T/P/L cell text: "…" while probing, "—" when down or unmeasured, otherwise milliseconds
 * *without* the "ms" suffix — the unit lives in the legend, and three suffixes per row cost
 * narrow screens up to 50dp of protocol name.
 */
internal fun cliProtocolMetricCell(status: CliProtocolStatus, value: Long?): String = when (status) {
    CliProtocolStatus.TESTING -> CLI_PROTO_METRIC_TESTING
    CliProtocolStatus.DOWN -> CLI_PROTO_METRIC_MISSING
    else -> value?.toString() ?: CLI_PROTO_METRIC_MISSING
}

private fun protocolRowPresentation(
    state: ProfilesRouteUiState,
    profile: Profile,
    option: ProfileProtocolOption,
): CliProtocolRowPresentation {
    val active = option.id == profile.selectedProtocolOptionId || option.isSelected
    val status = cliProtocolStatus(
        testing = state.smartProfileMetricsRefreshingOptionIdByProfileId[profile.id] == option.id,
        down = option.id in state.smartProfileDownOptionIds(profile.id),
        enabled = option.enabled,
        active = active,
        measured = option.id in state.smartProfileMetricsUpdatedAt(profile.id),
    )
    val best = state.recommendedProtocolOptionByProfileId[profile.id]
    val ranked = state.recommendedProtocolOptionsByProfileId[profile.id].orEmpty()
    return CliProtocolRowPresentation(
        name = option.displayName,
        active = active,
        enabled = option.enabled,
        status = status,
        stars = when {
            option.id == best -> "★★"
            option.id in ranked -> "★"
            else -> ""
        },
        connect = cliProtocolMetricCell(status, state.smartProfileConnectDurations(profile.id)[option.id]),
        ping = cliProtocolMetricCell(status, state.smartProfileServerPings(profile.id)[option.id]),
        latency = cliProtocolMetricCell(status, state.smartStartRememberedLatency(profile.id)[option.id]),
    )
}

@Composable
private fun rememberCliProtocolColumnWidths(
    state: ProfilesRouteUiState,
    profile: Profile,
    hostWidth: Dp,
): CliProtocolColumnWidths {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // CliType.* are composable getters: read before remember and kept in the keys, or a font
    // change never remeasures (same trap as CliDropdownRow). The keys are exactly this
    // profile's three metric maps — wider (all state) recomputes every tick inside LazyColumn,
    // narrower (without the values) lets columns shift the moment TEST replaces "—" with a
    // number.
    val metricStyle = CliType.small
    val statusStyle = CliType.body
    val connect = state.smartProfileConnectDurations(profile.id)
    val ping = state.smartProfileServerPings(profile.id)
    val latency = state.smartStartRememberedLatency(profile.id)
    val widthsPx = remember(connect, ping, latency, metricStyle, statusStyle) {
        CliProtocolColumnWidthsPx(
            connect = textMeasurer.metricColumnWidthPx(metricStyle, CLI_PROTO_HEADER_CONNECT, connect),
            ping = textMeasurer.metricColumnWidthPx(metricStyle, CLI_PROTO_HEADER_PING, ping),
            latency = textMeasurer.metricColumnWidthPx(metricStyle, CLI_PROTO_HEADER_LATENCY, latency),
            status = textMeasurer.statusColumnWidthPx(statusStyle),
        )
    }
    return with(density) {
        // T/P/L share one width (the max of the three measured) so columns stay symmetric
        // across profiles; the host-fraction cap protects the name on narrow screens — longer
        // values ellipsize, but the grid holds.
        val metric = maxOf(widthsPx.connect, widthsPx.ping, widthsPx.latency).toDp()
            .coerceAtMost(hostWidth * CLI_PROTOCOL_METRIC_MAX_HOST_FRACTION)
        CliProtocolColumnWidths(
            connect = metric,
            ping = metric,
            latency = metric,
            // The glyph is narrower, but the column stays at the touch-target floor: in the
            // sheet it *is* the toggle, and the table must match in both hosts.
            status = widthsPx.status.toDp().coerceAtLeast(CliProtocolStatusMinWidth),
        )
    }
}

private fun TextMeasurer.metricColumnWidthPx(
    style: TextStyle,
    header: String,
    values: Map<String, Long>,
): Int {
    val candidates = values.values.mapTo(
        mutableListOf(header, CLI_PROTO_METRIC_MISSING, CLI_PROTO_METRIC_TESTING),
    ) { it.toString() }
    return candidates.maxOf { measure(text = it, style = style).size.width }
}

private fun TextMeasurer.statusColumnWidthPx(style: TextStyle): Int =
    (CliProtocolStatus.entries.map { it.glyph } + CLI_PROTO_HEADER_STATUS)
        .maxOf { measure(text = it, style = style).size.width }

private fun CliProtocolStatus.color(colors: CliColors): Color = when (this) {
    CliProtocolStatus.TESTING -> colors.warn
    CliProtocolStatus.DOWN -> colors.err
    CliProtocolStatus.OFF -> colors.faint
    CliProtocolStatus.ACTIVE, CliProtocolStatus.READY -> colors.ok
    CliProtocolStatus.UNTESTED -> colors.dim
}

private fun CliProtocolRowPresentation.markerColor(colors: CliColors): Color = when {
    !enabled -> colors.faint
    active -> colors.accent
    else -> colors.faint
}

private fun CliProtocolRowPresentation.nameColor(colors: CliColors): Color = when {
    !enabled -> colors.faint
    active -> colors.fg
    else -> colors.dim
}

private data class CliProtocolRowPresentation(
    val name: String,
    val active: Boolean,
    val enabled: Boolean,
    val stars: String,
    val status: CliProtocolStatus,
    val connect: String,
    val ping: String,
    val latency: String,
) {
    val marker: String
        get() = when {
            !enabled -> "· "
            active -> "> "
            else -> "  "
        }
}

private data class CliProtocolColumnWidths(
    val connect: Dp,
    val ping: Dp,
    val latency: Dp,
    val status: Dp,
)

private data class CliProtocolColumnWidthsPx(
    val connect: Int,
    val ping: Int,
    val latency: Int,
    val status: Int,
)

private const val CLI_PROTO_HEADER_CONNECT = "T"
private const val CLI_PROTO_HEADER_PING = "P"
private const val CLI_PROTO_HEADER_LATENCY = "L"
private const val CLI_PROTO_HEADER_STATUS = "S"
private const val CLI_PROTO_METRIC_MISSING = "—"
private const val CLI_PROTO_METRIC_TESTING = "…"

private val CliProtocolStatusMinWidth = 48.dp

// Below this host width the inter-column gaps shrink 4dp -> 2dp; every dp saved goes to the
// protocol name.
private val CliProtocolCompactHostWidth = 340.dp

// Cap per metric column as a fraction of host width: three columns <= 45%.
private const val CLI_PROTOCOL_METRIC_MAX_HOST_FRACTION = 0.15f
