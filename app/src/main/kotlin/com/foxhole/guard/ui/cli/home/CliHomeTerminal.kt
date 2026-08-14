package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.networkUp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliSectionDivider
import com.foxhole.guard.ui.cli.components.CliStatusDot
import com.foxhole.guard.ui.cli.components.CliTypewriterText
import com.foxhole.guard.ui.cli.components.PIXEL_CAP_HEIGHT_RATIO
import com.foxhole.guard.ui.cli.fox.CliFoxHero
import com.foxhole.guard.ui.cli.settings.rememberCliAppIcon
import kotlinx.coroutines.delay

@Composable
internal fun CliTerminalPanel(
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    listState: LazyListState,
    followsOutput: Boolean,
    onFollowsOutputChanged: (Boolean) -> Unit,
    onInteraction: () -> Unit,
    onClearRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val timestampMetrics = rememberCliTerminalTimestampMetrics()
    Column(modifier = modifier) {
        CliTerminalHeader(home = home)
        CliSectionDivider()
        // Only the terminal body owns the clear-history hold. The brand/status header above is
        // deliberately inert, so an ordinary attempt to inspect the status cannot raise a sheet.
        CliPanel(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(CLI_HOME_TERMINAL_TAG),
            background = colors.bg,
            onClick = onInteraction,
            onLongClick = {
                onInteraction()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClearRequested()
            },
        ) {
            var outputLayoutRevision by remember { mutableIntStateOf(0) }
            // Runtime callbacks can arrive while the command is still being typed. Keep the state
            // hot, but do not draw its live row until the prompt has committed: a real terminal
            // never prints a status through the command currently being entered.
            val visibleProgress = terminalProgressForDisplay(terminal.progress, terminal.promptText)
            // A real user drag owns whether the terminal follows output. Programmatic positioning
            // uses scrollToItem (no animated scroll), so it never masquerades as a history gesture.
            LaunchedEffect(listState, terminal, visibleProgress?.id) {
                snapshotFlow {
                    Triple(
                        listState.isScrollInProgress,
                        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                        terminalOutputBottomIndex(
                            lineCount = terminal.lines.size,
                            hasVisibleProgress = visibleProgress != null,
                        ),
                    )
                }.collect { (scrolling, lastVisibleIndex, lastIndex) ->
                    if (scrolling) {
                        onFollowsOutputChanged(
                            shouldAutoScrollTerminal(
                                lastIndex = lastIndex,
                                lastVisibleIndex = lastVisibleIndex,
                                userScrollInProgress = false,
                            ),
                        )
                    }
                }
            }
            // Keyed by the last id, not size: the ring buffer eventually keeps a fixed size. The
            // root-owned follow flag also catches up after navigating back from another screen.
            LaunchedEffect(
                terminal.lines.lastOrNull()?.id,
                visibleProgress?.id,
                visibleProgress?.text,
                terminal.promptText,
                outputLayoutRevision,
                followsOutput,
            ) {
                if (!followsOutput || listState.isScrollInProgress) return@LaunchedEffect
                val lastIndex = terminalOutputBottomIndex(
                    lineCount = terminal.lines.size,
                    hasVisibleProgress = visibleProgress != null,
                )
                if (lastIndex >= 0) {
                    // Wait one frame so a restored journal/new ring-buffer head is laid out first.
                    withFrameNanos { }
                    val layout = listState.layoutInfo
                    val anchorHeightPx = with(density) { TERMINAL_OUTPUT_BOTTOM_GAP.roundToPx() }
                    listState.scrollToItem(
                        index = lastIndex,
                        // scrollToItem(index) top-aligns the trailing anchor, which puts the real
                        // last line just above the viewport. Pin the anchor to the bottom instead:
                        // completed output then remains immediately above the prompt/cursor row.
                        scrollOffset = terminalBottomScrollOffset(
                            viewportStartOffset = layout.viewportStartOffset,
                            viewportEndOffset = layout.viewportEndOffset,
                            anchorHeightPx = anchorHeightPx,
                        ),
                    )
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
            // Runtime and probe layers have their own shorter timeouts. This last UI fence only
            // guarantees that a dropped terminal callback cannot leave an immortal spinner.
            LaunchedEffect(visibleProgress?.id, visibleProgress?.text) {
                val live = visibleProgress ?: return@LaunchedEffect
                delay(CONNECTION_PROGRESS_WATCHDOG_MS)
                terminal.expireProgress(live.id)
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
                        // Persistent output never types underneath the live status row. Final
                        // identity/result lines type only after that row has disappeared.
                        isLast = index == lastIndex && visibleProgress == null,
                        timestampMetrics = timestampMetrics,
                        terminal = terminal,
                        onOutputHeightChanged = { outputLayoutRevision += 1 },
                    )
                }
                visibleProgress?.let { live ->
                    item(key = "terminal-progress-${live.id}") {
                        CliTerminalProgressRow(progress = live, timestampMetrics = timestampMetrics)
                    }
                }
                // Always scroll to a real trailing item rather than merely to the last output row.
                // A wrapped/typewriting row can grow after scrollToItem() runs; this anchor keeps
                // its full height above the persistent prompt/cursor row instead of hiding its tail.
                item(key = "terminal-bottom-anchor") {
                    Spacer(modifier = Modifier.height(TERMINAL_OUTPUT_BOTTOM_GAP))
                }
            }
            CliPromptRow(
                terminal = terminal,
                modifier = Modifier.offset(y = TERMINAL_PROMPT_VISUAL_OFFSET),
            )
        }
    }
}

/** A status callback may update state while the command is typing, but cannot draw through it. */
internal fun terminalProgressForDisplay(
    progress: CliTerminalProgress?,
    promptText: String?,
): CliTerminalProgress? = progress.takeIf { promptText == null }

internal fun terminalOutputBottomIndex(
    lineCount: Int,
    hasVisibleProgress: Boolean,
): Int = lineCount.coerceAtLeast(0) + if (hasVisibleProgress) 1 else 0

/** Places the trailing anchor at the viewport's bottom instead of top-aligning and hiding output. */
internal fun terminalBottomScrollOffset(
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    anchorHeightPx: Int,
): Int = -(
    (viewportEndOffset - viewportStartOffset).coerceAtLeast(0) - anchorHeightPx.coerceAtLeast(0)
    ).coerceAtLeast(0)

/** Follow new output only from the bottom edge; one partly visible trailing row counts as near it. */
internal fun shouldAutoScrollTerminal(
    lastIndex: Int,
    lastVisibleIndex: Int?,
    userScrollInProgress: Boolean,
): Boolean {
    if (lastIndex < 0 || userScrollInProgress) return false
    val visible = lastVisibleIndex ?: return true
    return visible >= lastIndex - TERMINAL_BOTTOM_PROXIMITY_ROWS
}

/** One spinner row whose text is replaced in place until the leg has a final identity. */
@Composable
private fun CliTerminalProgressRow(
    progress: CliTerminalProgress,
    timestampMetrics: CliTerminalTimestampMetrics,
) {
    val colors = LocalCliColors.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var frame by remember(progress.id) { mutableIntStateOf(0) }
    LaunchedEffect(progress.id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(PROGRESS_SPINNER_STEP_MS)
                frame = (frame + 1) % PROGRESS_SPINNER_FRAMES.size
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CliTerminalTimestamp(progress.timestampMs, timestampMetrics)
        Text(
            text = PROGRESS_SPINNER_FRAMES[frame],
            style = CliType.small,
            color = cliLineToneColor(progress.tone),
            maxLines = 1,
            modifier = Modifier.width(PROGRESS_SPINNER_WIDTH),
        )
        Text(
            text = progress.text,
            style = CliType.small,
            color = cliLineToneColor(progress.tone),
            maxLines = BODY_MAX_LINES,
        )
    }
}

// Timestamp-slot sample: '0' is LanaPixel's widest decimal glyph.
private const val TIMESTAMP_SAMPLE = "00:00:00"

private data class CliTerminalTimestampMetrics(
    val bracketSlotWidth: Dp,
    val timeSlotWidth: Dp,
    val totalWidth: Dp,
)

/**
 * Fixed timestamp grid measured from the widest sample. LanaPixel digits are proportional, so
 * measuring each real clock independently would move the terminal body's left edge every second.
 */
@Composable
private fun rememberCliTerminalTimestampMetrics(): CliTerminalTimestampMetrics {
    val style = CliType.small
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density, style) {
        with(density) {
            val bracketSlotWidth = maxOf(
                measurer.measure("[", style).size.width,
                measurer.measure("]", style).size.width,
            ).toDp()
            val timeSlotWidth = measurer.measure(TIMESTAMP_SAMPLE, style).size.width.toDp()
            CliTerminalTimestampMetrics(
                bracketSlotWidth = bracketSlotWidth,
                timeSlotWidth = timeSlotWidth,
                totalWidth = bracketSlotWidth * 2 + timeSlotWidth + TIMESTAMP_INNER_GAP * 2 + CliSpacing.sm,
            )
        }
    }
}

/** One timestamp grid shared by persisted output and the replace-in-place progress row. */
@Composable
private fun CliTerminalTimestamp(
    timestampMs: Long,
    metrics: CliTerminalTimestampMetrics,
) {
    val colors = LocalCliColors.current
    val bracketColor = lerp(colors.dim, colors.fg, TIMESTAMP_BRACKET_LIGHTEN)
    Row(
        modifier = Modifier.width(metrics.totalWidth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "[",
            style = CliType.small,
            color = bracketColor,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(metrics.bracketSlotWidth),
        )
        Spacer(modifier = Modifier.width(TIMESTAMP_INNER_GAP))
        Text(
            text = com.foxhole.guard.ui.cli.CliFormat.clock(timestampMs),
            style = CliType.small,
            color = colors.dim,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(metrics.timeSlotWidth),
        )
        Spacer(modifier = Modifier.width(TIMESTAMP_INNER_GAP))
        Text(
            text = "]",
            style = CliType.small,
            color = bracketColor,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(metrics.bracketSlotWidth),
        )
        Spacer(modifier = Modifier.width(CliSpacing.sm))
    }
}

private const val BLOCK_ROW_STEP_MS = 45L
private const val PROGRESS_SPINNER_STEP_MS = 120L
private const val CONNECTION_PROGRESS_WATCHDOG_MS = 45_000L
private val PROGRESS_SPINNER_WIDTH = 12.dp
private val TIMESTAMP_INNER_GAP = 2.dp
private const val TIMESTAMP_BRACKET_LIGHTEN = 0.35f
private val TERMINAL_OUTPUT_BOTTOM_GAP = 4.dp
private val TERMINAL_PROMPT_VISUAL_OFFSET = 3.dp
private val PROGRESS_SPINNER_FRAMES = arrayOf("|", "/", "—", "\\")

// Keys are short by canon ("VPN", "rest"); values carry ip+geo and app lists, so more width.
private const val KEY_COLUMN_WEIGHT = 1f
private const val VALUE_COLUMN_WEIGHT = 1.4f

/**
 * The bottom `fox > █` line doubles as the live command input: a pressed button's
 * command types out here character by character (cursor blinking at its tail), then
 * commits into the log as an ordinary `>` line.
 */
@Composable
private fun CliPromptRow(
    terminal: CliTerminalState,
    modifier: Modifier = Modifier,
) {
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
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
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
 * Brand header: the animated fox, "FoxHole Guard" in the two logo neons (two lines on narrow
 * screens; typed once per cold start), then one responsive status line. The fox is deliberately
 * compact: its height matches the title+status block instead of making the header taller than its
 * text. The terminal below is separated from this inert header and owns its clear-history gesture.
 */
@Composable
private fun CliTerminalHeader(home: com.foxhole.guard.ui.HomeRouteUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CliFoxHero(size = HOME_HEADER_FOX_SIZE)
        Spacer(modifier = Modifier.width(CliSpacing.md))
        BoxWithConstraints {
            val availableHeaderWidth = maxWidth
            val brand = CliType.display.copy(fontSize = 20.sp, lineHeight = 22.sp)
            val brandFitsOneLine = maxWidth >= 224.dp
            Column {
                CliBrandTitle(oneLine = brandFitsOneLine, style = brand)
                Spacer(modifier = Modifier.height(CliSpacing.xs))
                val torOnlyLive = isTorOnlyLive(home)
                val runtimes = activeRuntimes(home = home, torOnlyLive = torOnlyLive)
                val firewallLive =
                    cliFirewallLive(settings = home.settings, connection = home.connection, runtimes = runtimes)
                val i2pConnected = runtimes.i2p && home.i2pPhase.phase.networkUp
                val word = cliStatusWordFor(
                    state = home.connection.routeState(),
                    runtimes = runtimes,
                    i2pConnected = i2pConnected,
                    firewallLive = firewallLive,
                )
                val colors = LocalCliColors.current
                val connected = word in CONNECTED_STATUS_WORDS
                val statusColor = if (connected) colors.ok else cliStatusWordColor(word)
                val dotColor = if (connected) colors.info else statusColor
                // A step under body: the word now names the route it is connected to, so it is
                // three times longer than "connected" was and no longer wants a headline size.
                //
                // The line box is pinned to lineHeight — font padding off, half-leading split
                // evenly — so every line stands exactly as tall as the next whatever glyphs it
                // carries, and the dot beside it keeps matching in both languages.
                val baseStatusStyle = CliType.body.copy(
                    fontSize = 15.sp,
                    lineHeight = STATUS_LINE_HEIGHT,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.None,
                    ),
                )
                // ONE text, several spans — not separate Texts in a row. The stable prefix remains
                // neon while each live route keeps its own product colour.
                val line = buildAnnotatedString {
                    if (!connected) {
                        withStyle(SpanStyle(color = statusColor)) {
                            append(cliStatusWordText(word).uppercase())
                        }
                    } else {
                        withStyle(SpanStyle(color = colors.info)) {
                            append(stringResource(R.string.cli_home_status_connected_prefix).uppercase())
                        }
                        val tokens = buildList {
                            if (runtimes.vpn) {
                                add(stringResource(R.string.cli_home_status_net_vpn) to colors.vpn)
                            }
                            if (runtimes.tor) {
                                add(stringResource(R.string.cli_home_status_net_tor) to colors.tor)
                            }
                            if (i2pConnected) {
                                add(stringResource(R.string.cli_home_status_net_i2p) to colors.i2p)
                            }
                        }
                        tokens.forEachIndexed { index, (label, color) ->
                            withStyle(SpanStyle(color = colors.info)) {
                                append(
                                    if (index == 0) {
                                        " "
                                    } else {
                                        stringResource(R.string.cli_home_status_network_joiner)
                                    },
                                )
                            }
                            withStyle(SpanStyle(color = color)) { append(label.uppercase()) }
                        }
                    }
                }
                // The status is a fixed single line. Measure it at the preferred size, then scale
                // only when the remaining width beside the icon is too small.
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val preferredTextWidth = remember(measurer, line, baseStatusStyle) {
                    measurer.measure(
                        text = line,
                        style = baseStatusStyle,
                        maxLines = 1,
                        softWrap = false,
                    ).size.width.toFloat()
                }
                val statusScale = with(density) {
                    val preferredDotAndGapWidth =
                        baseStatusStyle.fontSize.toPx() * PIXEL_CAP_HEIGHT_RATIO + STATUS_DOT_GAP.toPx()
                    cliStatusScale(
                        availableWidthPx = availableHeaderWidth.toPx(),
                        textWidthPx = preferredTextWidth + preferredDotAndGapWidth,
                    )
                }
                val statusStyle = baseStatusStyle.copy(
                    fontSize = (15f * statusScale).sp,
                    lineHeight = (STATUS_LINE_HEIGHT.value * statusScale).sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CliStatusDot(
                        color = dotColor,
                        fontSize = statusStyle.fontSize,
                        pulsing = home.connection.isRouteTransition(),
                        // Pixel glyphs sit optically above the line box centre. Lift the disc by
                        // one hard pixel step so its centre follows the first capital, not the
                        // font's descender space.
                        modifier = Modifier.offset(y = STATUS_DOT_VERTICAL_OFFSET * statusScale),
                    )
                    Spacer(modifier = Modifier.width(STATUS_DOT_GAP * statusScale))
                    Text(
                        text = line,
                        style = statusStyle,
                        maxLines = STATUS_WORD_MAX_LINES,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
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
            CliTypewriterText(text = "FoxHole ", style = style, color = colors.accent, instant = instant)
            CliTypewriterText(
                text = "Guard",
                style = style,
                color = colors.info,
                instant = instant,
                onFullyTyped = { brandHeaderTypedOnce = true },
            )
        }
    } else {
        Column {
            CliTypewriterText(text = "FoxHole", style = style, color = colors.accent, instant = instant)
            CliTypewriterText(
                text = "Guard",
                style = style,
                color = colors.info,
                instant = instant,
                onFullyTyped = { brandHeaderTypedOnce = true },
            )
        }
    }
}

/** The word itself; which word to print is decided by [cliStatusWordFor]. */
@Composable
private fun cliStatusWordText(word: CliStatusWord): String = when (word) {
    CliStatusWord.CONNECTING -> stringResource(R.string.cli_home_status_connecting)
    CliStatusWord.RECONNECTING -> stringResource(R.string.cli_home_status_reconnecting)
    CliStatusWord.DISCONNECTING -> stringResource(R.string.cli_home_state_disconnecting)
    CliStatusWord.ERROR -> stringResource(R.string.cli_home_status_error)
    CliStatusWord.VPN_TOR -> stringResource(R.string.cli_home_status_connected_vpn_tor)
    CliStatusWord.VPN -> stringResource(R.string.cli_home_status_connected_vpn)
    CliStatusWord.TOR -> stringResource(R.string.cli_home_status_connected_tor)
    CliStatusWord.I2P -> stringResource(R.string.cli_home_status_i2p_connected)
    CliStatusWord.FIREWALL -> stringResource(R.string.cli_home_status_firewall_active)
    CliStatusWord.NONE -> stringResource(R.string.cli_home_status_none)
}

/** The dot's colour, off the same decision as the word — the two must never disagree. */
@Composable
private fun cliStatusWordColor(word: CliStatusWord): Color {
    val colors = LocalCliColors.current
    return when (word) {
        CliStatusWord.CONNECTING, CliStatusWord.RECONNECTING, CliStatusWord.DISCONNECTING -> colors.warn
        CliStatusWord.ERROR -> colors.err
        CliStatusWord.TOR, CliStatusWord.VPN_TOR -> colors.tor
        CliStatusWord.VPN -> colors.vpn
        CliStatusWord.I2P -> colors.i2p
        CliStatusWord.FIREWALL -> colors.firewall
        CliStatusWord.NONE -> colors.dim
    }
}

private val CONNECTED_STATUS_WORDS = setOf(
    CliStatusWord.VPN,
    CliStatusWord.TOR,
    CliStatusWord.VPN_TOR,
    CliStatusWord.I2P,
)

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
        CliLineTone.I2P -> colors.i2p
        CliLineTone.FIREWALL -> colors.firewall
        CliLineTone.DNS_FILTER -> colors.dnsFilter
    }
}

/**
 * One log line. The timestamp lives in a fixed-width slot [timestampMetrics] so the body's left edge
 * holds across lines; a non-null `value` renders two columns (key left on weight, value pushed
 * right). Proportional LanaPixel cannot align with spaces — only layout aligns.
 *
 * Nothing here ellipsizes at one line any more: a line too long for the width wraps onto a second
 * one. Cutting a route or a package name mid-word hid exactly the end that identified it.
 */
@Composable
private fun CliTerminalLineRow(
    line: CliTerminalLine,
    isLast: Boolean,
    timestampMetrics: CliTerminalTimestampMetrics,
    terminal: CliTerminalState,
    onOutputHeightChanged: () -> Unit,
) {
    val colors = LocalCliColors.current
    val toneColor = cliLineToneColor(line.tone)
    // Prompt lines render the `> ` glyph as a separate accent segment (templates may carry
    // it inside the text - strip it so the glyph never doubles).
    val body = if (line.prompt) line.text.removePrefix("> ") else line.text
    // The claim outlives this composable, so a line types when it arrives and never again — coming
    // back to home from another screen used to replay the last line's typing every single time.
    val typing = remember(line.id) { isLast && terminal.claimTyping(line.id) }
    var visibleChars by remember(line.id) { mutableIntStateOf(if (typing) 0 else body.length) }
    var measuredHeightPx by remember(line.id) { mutableIntStateOf(0) }
    LaunchedEffect(line.id) {
        if (typing && visibleChars < body.length) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                if (isLast && size.height != measuredHeightPx) {
                    measuredHeightPx = size.height
                    onOutputHeightChanged()
                } else {
                    measuredHeightPx = size.height
                }
            },
    ) {
        CliTerminalTimestamp(line.timestampMs, timestampMetrics)
        if (line.prompt) {
            Text(
                text = "> ",
                style = CliType.small,
                color = colors.accent,
                maxLines = 1,
            )
        }
        if (line.value == null && line.packages.isEmpty()) {
            // Country flag before the text (status/exit-ip), typed together with the line.
            if (line.flagCountry != null && visibleChars > 0) {
                CliFlagIcon(countryCode = line.flagCountry, style = CliType.small)
                Spacer(modifier = Modifier.width(CliSpacing.xs))
            }
            Text(
                text = body.take(visibleChars),
                style = CliType.small,
                color = toneColor,
                maxLines = BODY_MAX_LINES,
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

/** Two columns of one log line: key on weight left, icons + flag + value pushed right. */
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
        maxLines = BODY_MAX_LINES,
        modifier = Modifier.weight(KEY_COLUMN_WEIGHT),
    )
    Row(
        modifier = Modifier.weight(VALUE_COLUMN_WEIGHT),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (valueVisible) {
            line.packages.forEach { packageName ->
                CliTerminalAppIcon(packageName = packageName)
                Spacer(modifier = Modifier.width(CliSpacing.xs))
            }
            if (line.flagCountry != null) {
                CliFlagIcon(countryCode = line.flagCountry, style = CliType.small)
                Spacer(modifier = Modifier.width(CliSpacing.xs))
            }
            if (line.value != null) {
                Text(
                    text = line.value,
                    style = CliType.small,
                    color = cliLineToneColor(line.valueTone),
                    maxLines = BODY_MAX_LINES,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * A pinned app in the value column, drawn as its launcher icon at the line's own type size. The
 * names never fitted: three of them ellipsized into "Telegr…, WhatsA…, +4", where the icons are
 * recognised at a glance and six of them fit the same width.
 *
 * The version stamps the icon cache keys on are not carried by a log line, so an app that changes
 * its icon keeps the cached one until the process restarts — the alternative is holding the whole
 * installed-app inventory inside the terminal history.
 */
@Composable
private fun CliTerminalAppIcon(packageName: String) {
    val size = with(LocalDensity.current) { CliType.small.fontSize.toDp() }
    val icon = rememberCliAppIcon(
        packageName = packageName,
        versionCode = null,
        lastUpdateTime = null,
        bitmapSize = size,
    )
    if (icon == null) {
        Spacer(modifier = Modifier.size(size))
        return
    }
    Image(
        bitmap = icon,
        contentDescription = null,
        modifier = Modifier.size(size),
    )
}

// Notifications wrap naturally through two, three or four rows; only the fifth is clipped.
private const val BODY_MAX_LINES = 4
private const val TERMINAL_BOTTOM_PROXIMITY_ROWS = 1

// Status is always one responsive line beside the fox.
private const val STATUS_WORD_MAX_LINES = 1

// The status line's fixed box: every line stands this tall whatever glyphs it carries.
private val STATUS_LINE_HEIGHT = 18.sp
private val STATUS_DOT_GAP = 6.dp
private val STATUS_DOT_VERTICAL_OFFSET = (-2).dp
private const val MIN_STATUS_SCALE = 0.55f
private val HOME_HEADER_FOX_SIZE = 58.dp

internal fun cliStatusScale(
    availableWidthPx: Float,
    textWidthPx: Float,
): Float {
    val contentWidth = textWidthPx.coerceAtLeast(0f)
    val usableWidth = availableWidthPx.coerceAtLeast(0f)
    if (contentWidth <= 0f || usableWidth >= contentWidth) return 1f
    return (usableWidth / contentWidth).coerceIn(MIN_STATUS_SCALE, 1f)
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

@Composable
internal fun stateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.IDLE -> stringResource(R.string.cli_home_state_offline)
    ConnectionState.CONNECTING -> stringResource(R.string.cli_home_state_connecting)
    ConnectionState.CONNECTED -> stringResource(R.string.cli_home_state_connected)
    ConnectionState.RECONNECTING -> stringResource(R.string.cli_home_state_reconnecting)
    ConnectionState.DISCONNECTING -> stringResource(R.string.cli_home_state_disconnecting)
    ConnectionState.ERROR -> stringResource(R.string.cli_home_state_error)
}
