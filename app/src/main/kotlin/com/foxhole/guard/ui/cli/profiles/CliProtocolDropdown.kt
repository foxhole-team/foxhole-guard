package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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
import com.foxhole.guard.ui.cli.components.CLI_MENU_ROW_MIN_HEIGHT
import com.foxhole.guard.ui.cli.components.CliActiveDot
import com.foxhole.guard.ui.cli.components.CliCheckGlyph
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliIcon
import com.foxhole.guard.ui.cli.components.CliLatencyKind
import com.foxhole.guard.ui.cli.components.CliLatencyTone
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.cliLatencyTone
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.smartProfileConnectDurations
import com.foxhole.guard.ui.smartProfileDownOptionIds
import com.foxhole.guard.ui.smartProfileMetricsUpdatedAt
import com.foxhole.guard.ui.smartProfileServerPings
import com.foxhole.guard.ui.smartStartRememberedLatency

@Composable
internal fun CliProtocolDropdown(
    viewModel: HomeViewModel,
    state: ProfilesRouteUiState,
    profile: Profile,
    onOptionSelected: () -> Unit,
    modifier: Modifier = Modifier,
    onOptionEnabledToggle: ((optionId: String, enabled: Boolean) -> Unit)? = null,
    showStatus: Boolean = true,
) {
    val colors = LocalCliColors.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cellSpacing = if (maxWidth < CliProtocolCompactHostWidth) 2.dp else CliSpacing.xs
        val widths = rememberCliProtocolColumnWidths(
            state = state,
            profile = profile,
            hostWidth = maxWidth,
            cellSpacing = cellSpacing,
        )
        val options = if (showStatus) {
            profile.protocolOptions
        } else {
            profile.protocolOptions.filter(ProfileProtocolOption::enabled)
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            CliProtocolTableHeader(
                widths = widths,
                cellSpacing = cellSpacing,
                showStatus = showStatus,
                nameLabel = stringResource(R.string.cli_prof_table_profile_name),
            )
            CliRowDivider()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                itemsIndexed(
                    items = options,
                    key = { _, option -> option.id },
                ) { optionIndex, option ->
                    if (optionIndex > 0) CliRowDivider()
                    CliProtocolOptionRow(
                        presentation = protocolRowPresentation(state, profile, option),
                        widths = widths,
                        cellSpacing = cellSpacing,
                        showStatus = showStatus,
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
                item(key = "test-legend") {
                    CliElbowLine(text = stringResource(R.string.cli_prof_test_legend), color = colors.faint)
                }
            }
        }
    }
}

@Composable
internal fun CliProtocolTableHeader(
    widths: CliProtocolColumnWidths,
    cellSpacing: Dp,
    showStatus: Boolean = true,
    nameLabel: String? = null,
) {
    val colors = LocalCliColors.current
    val headerStyle = cliProfileTableHeaderStyle()
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = CliSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nameLabel == null) {
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Text(
                text = nameLabel,
                style = headerStyle,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = CliSpacing.xs),
            )
        }
        CliProtocolTableCell(
            text = CLI_PROTO_HEADER_CONNECT,
            cellWidth = widths.connect,
            color = colors.faint,
            spacing = cellSpacing,
            style = headerStyle,
        )
        CliProtocolTableCell(
            text = CLI_PROTO_HEADER_PING,
            cellWidth = widths.ping,
            color = colors.faint,
            spacing = cellSpacing,
            style = headerStyle,
        )
        CliProtocolTableCell(
            text = CLI_PROTO_HEADER_LATENCY,
            cellWidth = widths.latency,
            color = colors.faint,
            spacing = cellSpacing,
            style = headerStyle,
        )
        if (showStatus) {
            Spacer(modifier = Modifier.width(cellSpacing))
            Box(modifier = Modifier.width(widths.status), contentAlignment = Alignment.Center) {
                Text(text = CLI_PROTO_HEADER_STATUS, style = headerStyle, color = colors.faint, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CliProtocolOptionRow(
    presentation: CliProtocolRowPresentation,
    widths: CliProtocolColumnWidths,
    cellSpacing: Dp,
    onClick: () -> Unit,
    onToggleEnabled: (() -> Unit)?,
    showStatus: Boolean = true,
) {
    val colors = LocalCliColors.current
    val statusModifier = if (onToggleEnabled == null) {
        Modifier
    } else {
        Modifier.cliPressable(onClick = onToggleEnabled)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = CLI_MENU_ROW_MIN_HEIGHT)
            .padding(horizontal = CliSpacing.xs)
            .combinedClickable(onClick = onClick, onLongClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (presentation.active) {
            CliActiveDot(active = true)
        }
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = presentation.name,
                style = CliType.body,
                color = presentation.nameColor(colors),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .weight(1f)
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = PROTOCOL_NAME_MARQUEE_DELAY_MS,
                        repeatDelayMillis = PROTOCOL_NAME_MARQUEE_REPEAT_MS,
                    ),
            )
            if (presentation.stars.isNotEmpty()) {
                Text(text = " ${presentation.stars}", style = CliType.body, color = colors.accent)
            }
        }
        CliProtocolTableCell(
            text = presentation.connect,
            cellWidth = widths.connect,
            color = protocolMetricColor(
                presentation.connectMs,
                presentation.status,
                CliLatencyKind.CONNECT,
                colors,
            ),
            spacing = cellSpacing,
        )
        CliProtocolTableCell(
            text = presentation.ping,
            cellWidth = widths.ping,
            color = protocolMetricColor(
                presentation.pingMs,
                presentation.status,
                CliLatencyKind.PROTOCOL,
                colors,
            ),
            spacing = cellSpacing,
        )
        CliProtocolTableCell(
            text = presentation.latency,
            cellWidth = widths.latency,
            color = protocolMetricColor(
                presentation.latencyMs,
                presentation.status,
                CliLatencyKind.PROTOCOL,
                colors,
            ),
            spacing = cellSpacing,
        )
        if (showStatus) {
            Spacer(modifier = Modifier.width(cellSpacing))
            Box(
                modifier = Modifier
                    .width(widths.status)
                    .defaultMinSize(minHeight = CLI_MENU_ROW_MIN_HEIGHT)
                    .then(statusModifier),
                contentAlignment = Alignment.Center,
            ) {
                if (onToggleEnabled != null) {
                    CliCheckGlyph(checked = presentation.enabled)
                } else {
                    CliProtocolStatusBadge(status = presentation.status)
                }
            }
        }
    }
}

@Composable
private fun CliProtocolStatusBadge(status: CliProtocolStatus) {
    val colors = LocalCliColors.current
    val tint = status.color(colors)
    val positive = status == CliProtocolStatus.ACTIVE || status == CliProtocolStatus.READY
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (positive) tint.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, tint.copy(alpha = if (positive) 0.9f else 0.65f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            positive -> CliIcon(
                id = R.drawable.lin_check,
                contentDescription = null,
                tint = tint,
                size = 12.dp,
            )
            status == CliProtocolStatus.OFF -> CliIcon(
                id = R.drawable.lin_power,
                contentDescription = null,
                tint = tint,
                size = 12.dp,
            )
            else -> Text(text = status.glyph, style = CliType.small, color = tint, maxLines = 1)
        }
    }
}

@Composable
internal fun CliProtocolTableCell(
    text: String,
    cellWidth: Dp,
    color: Color,
    spacing: Dp,
    style: TextStyle = CliType.small,
) {
    Spacer(modifier = Modifier.width(spacing))
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(cellWidth),
    )
}

internal enum class CliProtocolStatus(val glyph: String) {
    TESTING("[~]"),
    DOWN("[!]"),
    OFF("[ ]"),
    ACTIVE("[x]"),
    UNTESTED("[?]"),
    READY("[x]"),
}

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

internal fun cliProtocolMetricCell(status: CliProtocolStatus, value: Long?): String = when (status) {
    CliProtocolStatus.TESTING -> CLI_PROTO_METRIC_TESTING
    CliProtocolStatus.DOWN -> CLI_PROTO_METRIC_MISSING
    else -> value?.toString() ?: CLI_PROTO_METRIC_MISSING
}

internal fun protocolRowPresentation(
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
        connectMs = state.smartProfileConnectDurations(profile.id)[option.id],
        pingMs = state.smartProfileServerPings(profile.id)[option.id],
        latencyMs = state.smartStartRememberedLatency(profile.id)[option.id],
    )
}

@Composable
internal fun rememberCliProtocolColumnWidths(
    state: ProfilesRouteUiState,
    profile: Profile,
    hostWidth: Dp,
    cellSpacing: Dp,
): CliProtocolColumnWidths {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
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
        val status = widthsPx.status.toDp().coerceAtLeast(CliProtocolStatusMinWidth)
        val metric = cliProtocolMetricColumnWidthPx(
            measuredMaxPx = maxOf(widthsPx.connect, widthsPx.ping, widthsPx.latency),
            hostWidthPx = hostWidth.roundToPx(),
            statusPx = status.roundToPx(),
            spacingPx = cellSpacing.roundToPx(),
        ).toDp()
        CliProtocolColumnWidths(
            connect = metric,
            ping = metric,
            latency = metric,
            status = status,
        )
    }
}

internal fun cliProtocolMetricColumnWidthPx(
    measuredMaxPx: Int,
    hostWidthPx: Int,
    statusPx: Int,
    spacingPx: Int,
): Int {
    val capPx = (hostWidthPx * CLI_PROTOCOL_METRIC_MAX_HOST_FRACTION).toInt()
    if (measuredMaxPx >= capPx) return capPx
    val nameReservePx = (hostWidthPx * CLI_PROTOCOL_NAME_MIN_HOST_FRACTION).toInt()
    val evenSharePx =
        (hostWidthPx - nameReservePx - statusPx - CLI_PROTOCOL_GAP_COUNT * spacingPx) /
            CLI_PROTOCOL_METRIC_COLUMN_COUNT
    return evenSharePx.coerceIn(measuredMaxPx, capPx)
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

internal fun CliProtocolRowPresentation.nameColor(colors: CliColors): Color = when {
    !enabled -> colors.faint
    active -> colors.fg
    else -> colors.dim
}

internal fun protocolMetricColor(
    value: Long?,
    status: CliProtocolStatus,
    kind: CliLatencyKind,
    colors: CliColors,
): Color {
    if (status == CliProtocolStatus.DOWN) return colors.err
    if (status == CliProtocolStatus.TESTING) return colors.warn
    return when (cliLatencyTone(value, kind)) {
        CliLatencyTone.UNAVAILABLE -> colors.faint
        CliLatencyTone.NORMAL -> colors.ok
        CliLatencyTone.DEGRADED -> colors.warn
        CliLatencyTone.ELEVATED -> colors.alert
        CliLatencyTone.POOR -> colors.err
    }
}

internal data class CliProtocolRowPresentation(
    val name: String,
    val active: Boolean,
    val enabled: Boolean,
    val stars: String,
    val status: CliProtocolStatus,
    val connect: String,
    val ping: String,
    val latency: String,
    val connectMs: Long?,
    val pingMs: Long?,
    val latencyMs: Long?,
)

internal data class CliProtocolColumnWidths(
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
private const val PROTOCOL_NAME_MARQUEE_DELAY_MS = 1_200
private const val PROTOCOL_NAME_MARQUEE_REPEAT_MS = 1_000

private val CliProtocolStatusMinWidth = 48.dp

internal val CliProtocolCompactHostWidth = 340.dp

private const val CLI_PROTOCOL_METRIC_MAX_HOST_FRACTION = 0.15f

private const val CLI_PROTOCOL_NAME_MIN_HOST_FRACTION = 0.35f

private const val CLI_PROTOCOL_METRIC_COLUMN_COUNT = 3

private const val CLI_PROTOCOL_GAP_COUNT = 4
