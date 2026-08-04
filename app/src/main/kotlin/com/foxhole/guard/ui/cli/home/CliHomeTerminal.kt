package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliStatusDot
import com.foxhole.guard.ui.cli.components.CliTypewriterText
import com.foxhole.guard.ui.cli.fox.CliFoxHero
import kotlinx.coroutines.delay

@Composable
internal fun CliTerminalPanel(
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val listState = rememberLazyListState()
    val smallStyle = CliType.small
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Fixed-width timestamp slot measured from the widest sample "[00:00:00]". LanaPixel digits
    // differ in width ('1'=364, '0'=546 at upem 1000), so without the slot the body's left edge
    // wanders between lines and no column ever lines up.
    val prefixWidth = remember(measurer, density, smallStyle) {
        with(density) { measurer.measure(PREFIX_SAMPLE, smallStyle).size.width.toDp() } + CliSpacing.sm
    }
    CliPanel(
        modifier = modifier.testTag(CLI_HOME_TERMINAL_TAG),
        background = colors.bg,
    ) {
        CliTerminalHeader(home = home)
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        // Keyed by last line id, NOT list size: on ring overflow (MAX_LINES) the size freezes,
        // the effect stops restarting and new lines slide under the edge.
        LaunchedEffect(terminal.lines.lastOrNull()?.id) {
            if (terminal.lines.isNotEmpty()) {
                listState.animateScrollToItem(terminal.lines.size - 1)
            }
        }
        // A block reply (`status`) leaves ONE row per tick and only while the prompt is free —
        // otherwise the whole block would fall into the held queue in a single frame.
        LaunchedEffect(terminal.blockPending, terminal.promptText) {
            if (terminal.promptText != null) return@LaunchedEffect
            while (terminal.drainBlockRow()) {
                delay(BLOCK_ROW_STEP_MS)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // Monotonic id key: head-prune shifts positions, and without a key every append
            // rebound all visible rows. lastIndex is read OUTSIDE the item lambdas so an append
            // redraws only two rows (old last and new last).
            val lastIndex = terminal.lines.lastIndex
            itemsIndexed(terminal.lines, key = { _, line -> line.id }) { index, line ->
                CliTerminalLineRow(
                    line = line,
                    isLast = index == lastIndex,
                    prefixWidth = prefixWidth,
                )
            }
        }
        CliPromptRow(terminal = terminal)
    }
}

// Timestamp-slot sample: '0' is LanaPixel's widest decimal glyph.
private const val PREFIX_SAMPLE = "[00:00:00]"

private const val BLOCK_ROW_STEP_MS = 45L

// Keys are short by canon ("VPN", "rest"); values carry ip+geo and app lists, so more width.
private const val KEY_COLUMN_WEIGHT = 1f
private const val VALUE_COLUMN_WEIGHT = 1.4f

/**
 * The bottom `fox > █` line doubles as the live command input: a pressed button's
 * command types out here character by character (cursor blinking at its tail), then
 * commits into the log as an ordinary `>` line.
 */
@Composable
private fun CliPromptRow(terminal: CliTerminalState) {
    val colors = LocalCliColors.current
    val prompt = terminal.promptText
    val typedCount = terminal.promptTypedCount
    LaunchedEffect(prompt) {
        if (prompt != null) {
            while (terminal.promptTypedCount < prompt.length) {
                kotlinx.coroutines.delay(40L)
                terminal.promptTypedCount++
            }
            kotlinx.coroutines.delay(260L)
            terminal.commitPrompt()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "fhg > ", style = CliType.body, color = colors.accent)
        if (prompt != null) {
            Text(
                text = prompt.take(typedCount),
                style = CliType.body,
                color = colors.fg,
                maxLines = 1,
            )
        }
        CliBlinkingCursor()
    }
}

/**
 * Brand header: animated fox logo, "FOXHOLE GUARD" in the two logo neons (two lines on narrow
 * screens; typed once per cold start). Below: the connection status word by the dot, then the
 * mode line "(vpn-split + tor)" in the role color. The firewall never appears here — it is a
 * filter, not a route (its mark lives only in the status window).
 */
@Composable
private fun CliTerminalHeader(home: com.foxhole.guard.ui.HomeRouteUiState) {
    val colors = LocalCliColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CliFoxHero()
        Spacer(modifier = Modifier.width(CliSpacing.md))
        BoxWithConstraints {
            val brand = CliType.display.copy(fontSize = 24.sp, lineHeight = 26.sp)
            // "FOXHOLE GUARD" ~13 Silkscreen glyphs at 24sp ≈ 260dp; narrower wraps to two lines.
            val brandFitsOneLine = maxWidth >= 268.dp
            Column {
                CliBrandTitle(oneLine = brandFitsOneLine, style = brand)
                Spacer(modifier = Modifier.height(CliSpacing.xs))
                val torOnlyLive = isTorOnlyLive(home)
                val statusColor = connectionStatusColor(home.connection, torOnlyLive)
                // Pulse only for a real route: a rising firewall is not "connecting".
                val busy = home.connection.isRouteTransition()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CliStatusDot(color = statusColor, pulsing = busy)
                    Spacer(modifier = Modifier.width(6.dp))
                    CliTypewriterText(
                        text = connectionStatusWord(home.connection, torOnlyLive),
                        color = statusColor,
                    )
                }
                val modeLine = connectionModeLine(home, torOnlyLive)
                if (modeLine != null) {
                    CliTypewriterText(
                        text = modeLine,
                        color = connectionModeColor(home, torOnlyLive),
                    )
                } else {
                    CliTypewriterText(text = terminalHeadline(home), color = colors.dim)
                }
            }
        }
    }
}

// Typed once per cold process start; afterwards renders instantly.
private var brandHeaderTypedOnce = false

@Composable
private fun CliBrandTitle(
    oneLine: Boolean,
    style: androidx.compose.ui.text.TextStyle,
) {
    val colors = LocalCliColors.current
    val instant = brandHeaderTypedOnce
    if (oneLine) {
        Row {
            CliTypewriterText(text = "FOXHOLE ", style = style, color = colors.accent, instant = instant)
            CliTypewriterText(
                text = "GUARD",
                style = style,
                color = colors.vpn,
                instant = instant,
                onFullyTyped = { brandHeaderTypedOnce = true },
            )
        }
    } else {
        Column {
            CliTypewriterText(text = "FOXHOLE", style = style, color = colors.accent, instant = instant)
            CliTypewriterText(
                text = "GUARD",
                style = style,
                color = colors.vpn,
                instant = instant,
                onFullyTyped = { brandHeaderTypedOnce = true },
            )
        }
    }
}

/**
 * Status word by the dot. A firewall alone is "no connections": it filters traffic but routes
 * nothing, so its snapshot arrives normalized to IDLE ([routeState]).
 */
@Composable
private fun connectionStatusWord(
    connection: ConnectionSnapshot,
    torOnlyLive: Boolean,
): String = when (connection.routeState()) {
    ConnectionState.CONNECTED -> stringResource(R.string.cli_home_status_connected)
    ConnectionState.CONNECTING -> stringResource(R.string.cli_home_status_connecting)
    ConnectionState.RECONNECTING -> stringResource(R.string.cli_home_status_reconnecting)
    ConnectionState.ERROR -> stringResource(R.string.cli_home_status_error)
    ConnectionState.IDLE ->
        if (torOnlyLive) {
            stringResource(R.string.cli_home_status_connected)
        } else {
            stringResource(R.string.cli_home_status_none)
        }
}

/**
 * Mode line "(vpn-split + tor-split)", built from live ROUTES: VPN/proxy and Tor. The firewall
 * is a background filter, not a route — firewall alone yields an empty list and a null line,
 * so the header honestly reads "no connections".
 */
private fun connectionModeParts(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torOnlyLive: Boolean,
): List<String> {
    val settings = home.settings
    val connection = home.connection
    val torActive = (connection.state == ConnectionState.CONNECTED && connection.torActive) || torOnlyLive
    val primaryConnected =
        connection.state == ConnectionState.CONNECTED &&
            connection.profileId != null &&
            connection.profileId != LOCAL_GUARD_PROFILE_ID &&
            connection.profileId != com.foxhole.core.model.TOR_ONLY_PROFILE_ID
    val vpnSplit = settings.expert.perAppRoutingMode != com.foxhole.core.model.PerAppRoutingMode.FULL_TUNNEL
    val torSplit = settings.privacyRoute.scope == com.foxhole.core.model.PrivacyRouteScope.SELECTED_APPS
    return buildList {
        if (primaryConnected) {
            when {
                connection.trafficMode == com.foxhole.core.model.TrafficMode.PROXY -> add("proxy")
                vpnSplit -> add("vpn-split")
                else -> add("vpn")
            }
        }
        if (torActive) add(if (torSplit && !torOnlyLive) "tor-split" else "tor")
    }
}

private fun connectionModeLine(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torOnlyLive: Boolean,
): String? {
    val parts = connectionModeParts(home, torOnlyLive)
    if (parts.isEmpty()) return null
    return parts.joinToString(separator = " + ", prefix = "(", postfix = ")")
}

@Composable
private fun connectionModeColor(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torOnlyLive: Boolean,
): Color {
    val colors = LocalCliColors.current
    val parts = connectionModeParts(home, torOnlyLive)
    return when {
        parts.any { it.startsWith("tor") } -> colors.tor
        parts.any { it.startsWith("vpn") || it == "proxy" } -> colors.vpn
        else -> colors.dim
    }
}

// Good/tolerable/bad tunnel-latency thresholds, edited in one place.
private const val LATENCY_OK_MAX_MS = 150L
private const val LATENCY_WARN_MAX_MS = 400L

@Composable
internal fun latencyColor(ms: Long?): Color {
    val colors = LocalCliColors.current
    return when {
        ms == null -> Color.Unspecified
        ms <= LATENCY_OK_MAX_MS -> colors.ok
        ms <= LATENCY_WARN_MAX_MS -> colors.warn
        else -> colors.err
    }
}

@Composable
private fun cliLineToneColor(tone: CliLineTone): Color {
    val colors = LocalCliColors.current
    return when (tone) {
        CliLineTone.PLAIN -> colors.fg
        CliLineTone.DIM -> colors.dim
        CliLineTone.ACCENT -> colors.accent
        CliLineTone.OK -> colors.ok
        CliLineTone.WARN -> colors.warn
        CliLineTone.ERR -> colors.err
        CliLineTone.INFO -> colors.info
        CliLineTone.VPN -> colors.vpn
        CliLineTone.TOR -> colors.tor
    }
}

/**
 * One log line. The timestamp lives in a fixed-width slot [prefixWidth] so the body's left edge
 * holds across lines; a non-null `value` renders two columns (key left on weight, value pushed
 * right). Proportional LanaPixel cannot align with spaces — only layout aligns.
 */
@Composable
private fun CliTerminalLineRow(
    line: CliTerminalLine,
    isLast: Boolean,
    prefixWidth: Dp,
) {
    val colors = LocalCliColors.current
    val toneColor = cliLineToneColor(line.tone)
    // Prompt lines render the `> ` glyph as a separate accent segment (templates may carry
    // it inside the text - strip it so the glyph never doubles).
    val body = if (line.prompt) line.text.removePrefix("> ") else line.text
    var visibleChars by remember(line) { mutableIntStateOf(if (isLast) 0 else body.length) }
    LaunchedEffect(line) {
        if (isLast && visibleChars < body.length) {
            // Frame clock instead of delay(10): same speed (~200 chars/s) but at most one
            // snapshot write per frame — the delay loop wrote up to 100/s over frames, and
            // without frames (background) typing now simply freezes.
            val startChars = visibleChars
            val startNanos = withFrameNanos { it }
            while (visibleChars < body.length) {
                withFrameNanos { now ->
                    val elapsedMs = (now - startNanos) / 1_000_000L
                    visibleChars = (startChars + (elapsedMs / 10L).toInt() * 2)
                        .coerceAtMost(body.length)
                }
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = line.prefix(),
            style = CliType.small,
            color = colors.dim,
            maxLines = 1,
            modifier = Modifier.width(prefixWidth),
        )
        if (line.prompt) {
            Text(
                text = "> ",
                style = CliType.small,
                color = colors.accent,
                maxLines = 1,
            )
        }
        if (line.value == null) {
            // Country flag before the text (status/exit-ip), typed together with the line.
            if (line.flagCountry != null && visibleChars > 0) {
                CliFlagIcon(countryCode = line.flagCountry, style = CliType.small)
                Spacer(modifier = Modifier.width(CliSpacing.xs))
            }
            Text(
                text = body.take(visibleChars),
                style = CliType.small,
                color = toneColor,
                maxLines = 2,
            )
        } else {
            CliTerminalKeyValueColumns(
                key = body.take(visibleChars),
                keyColor = toneColor,
                line = line,
                // The value appears once the key finishes typing: first "what", then "how much".
                valueVisible = visibleChars >= body.length,
            )
        }
    }
}

/** Two columns of one log line: key on weight left, flag + value pushed right. */
@Composable
private fun RowScope.CliTerminalKeyValueColumns(
    key: String,
    keyColor: Color,
    line: CliTerminalLine,
    valueVisible: Boolean,
) {
    Text(
        text = key,
        style = CliType.small,
        color = keyColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(KEY_COLUMN_WEIGHT),
    )
    Row(
        modifier = Modifier.weight(VALUE_COLUMN_WEIGHT),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (valueVisible) {
            if (line.flagCountry != null) {
                CliFlagIcon(countryCode = line.flagCountry, style = CliType.small)
                Spacer(modifier = Modifier.width(CliSpacing.xs))
            }
            Text(
                text = line.value.orEmpty(),
                style = CliType.small,
                color = cliLineToneColor(line.valueTone),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun CliBlinkingCursor() {
    val colors = LocalCliColors.current
    var on by remember { mutableStateOf(true) }
    // Gated on STARTED: the blink delay-loop is not frame-bound and would tick in background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                kotlinx.coroutines.delay(530L)
                on = !on
            }
        }
    }
    // Canvas block instead of the '█' glyph: the Noto fallback has a different line height and
    // blinking jerked the prompt up and down. A fixed-size rectangle does not jump.
    val cursorColor = if (on) colors.fg else Color.Transparent
    Canvas(modifier = Modifier.size(width = 7.dp, height = 13.dp)) {
        drawRect(color = cursorColor)
    }
}

// Header subtitle when there is no mode line. The firewall snapshot is normalized to IDLE
// ([routeState]), so it never prints "connected · <profile>" — it falls to tor phases/offline.
@Composable
private fun terminalHeadline(home: com.foxhole.guard.ui.HomeRouteUiState): String {
    val connection = home.connection
    val state = connection.routeState()
    return when {
        state == ConnectionState.CONNECTED ->
            stringResource(R.string.cli_home_head_connected, connection.profileName ?: "vpn") +
                if (connection.torActive) " +tor" else ""
        state == ConnectionState.CONNECTING ->
            stringResource(R.string.cli_home_head_connecting)
        state == ConnectionState.RECONNECTING ->
            stringResource(R.string.cli_home_head_reconnecting)
        state == ConnectionState.ERROR -> stringResource(R.string.cli_home_head_error)
        home.torPhase.phase == TorNetworkPhase.CONNECTED ->
            stringResource(R.string.cli_home_head_tor_only)
        home.torPhase.phase != TorNetworkPhase.OFFLINE ->
            stringResource(R.string.cli_home_head_tor_bootstrap, home.torPhase.progress ?: 0)
        else -> stringResource(R.string.cli_home_head_offline)
    }
}

@Composable
internal fun stateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.IDLE -> stringResource(R.string.cli_home_state_offline)
    ConnectionState.CONNECTING -> stringResource(R.string.cli_home_state_connecting)
    ConnectionState.CONNECTED -> stringResource(R.string.cli_home_state_connected)
    ConnectionState.RECONNECTING -> stringResource(R.string.cli_home_state_reconnecting)
    ConnectionState.ERROR -> stringResource(R.string.cli_home_state_error)
}
