package com.foxhole.guard.ui.cli.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.VisualStyle
import com.foxhole.core.model.networkUp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliMotion
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliScaledSp
import com.foxhole.guard.ui.cli.components.CliBadge
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliHeaderHelpButton
import com.foxhole.guard.ui.cli.components.CliHomeSectionGap
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliShimmerText
import com.foxhole.guard.ui.cli.components.CliStatusDot
import com.foxhole.guard.ui.cli.components.CliTypewriterText
import com.foxhole.guard.ui.cli.components.PIXEL_CAP_HEIGHT_RATIO
import com.foxhole.guard.ui.cli.components.cliFlagCode
import com.foxhole.guard.ui.cli.components.cliFlagIconSize
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
    onHelpRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val timestampMetrics = rememberCliTerminalTimestampMetrics()
    Column(modifier = modifier) {
        CliTerminalHeader(home = home, onHelpRequested = onHelpRequested)
        CliHomeSectionGap()
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
            val visibleProgress =
                terminalProgressForDisplay(terminal.bootProgress ?: terminal.progress, terminal.promptText)
            CliTerminalScrollEffects(
                terminal = terminal,
                listState = listState,
                visibleProgress = visibleProgress,
                followsOutput = followsOutput,
                onFollowsOutputChanged = onFollowsOutputChanged,
                outputLayoutRevision = outputLayoutRevision,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val lastIndex = terminal.lines.lastIndex
                itemsIndexed(terminal.lines, key = { _, line -> line.id }) { index, line ->
                    CliTerminalLineRow(
                        line = line,
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
                item(key = "terminal-bottom-anchor") {
                    Spacer(modifier = Modifier.height(TERMINAL_OUTPUT_BOTTOM_GAP))
                }
            }
            CliPromptRow(
                terminal = terminal,
                modifier = Modifier.offset(y = cliTerminalPromptOffset()),
            )
        }
    }
}

@Composable
@ReadOnlyComposable
private fun cliTerminalPromptOffset(): Dp =
    if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        TERMINAL_PROMPT_VISUAL_OFFSET_MODERN
    } else {
        TERMINAL_PROMPT_VISUAL_OFFSET
    }

internal fun terminalProgressForDisplay(
    progress: CliTerminalProgress?,
    promptText: String?,
): CliTerminalProgress? = progress.takeIf { promptText == null }

internal fun terminalOutputBottomIndex(
    lineCount: Int,
    hasVisibleProgress: Boolean,
): Int = lineCount.coerceAtLeast(0) + if (hasVisibleProgress) 1 else 0

internal fun terminalBottomScrollOffset(
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    anchorHeightPx: Int,
): Int = -(
    (viewportEndOffset - viewportStartOffset).coerceAtLeast(0) - anchorHeightPx.coerceAtLeast(0)
    ).coerceAtLeast(0)

internal fun shouldAutoScrollTerminal(
    lastIndex: Int,
    lastVisibleIndex: Int?,
    userScrollInProgress: Boolean,
): Boolean {
    if (lastIndex < 0 || userScrollInProgress) return false
    val visible = lastVisibleIndex ?: return true
    return visible >= lastIndex - TERMINAL_BOTTOM_PROXIMITY_ROWS
}

@Composable
private fun CliTerminalProgressRow(
    progress: CliTerminalProgress,
    timestampMetrics: CliTerminalTimestampMetrics,
) {
    val title = progress.title
    if (title == null) {
        CliTerminalProgressStepRow(
            progress = progress,
            leading = { CliTerminalTimestamp(progress.timestampMs, timestampMetrics) },
            style = CliType.small,
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CliTerminalTimestamp(progress.timestampMs, timestampMetrics)
            CliTerminalLeadSlot(
                icon = cliLineToneIcon(progress.titleTone, prompt = false),
                tint = cliLineToneColor(progress.titleTone)
            )
            Text(
                text = title,
                style = CliType.small,
                color = cliLineToneColor(progress.titleTone),
                maxLines = BODY_MAX_LINES,
            )
        }
        CliTerminalProgressStepRow(
            progress = progress,
            leading = {
                Spacer(
                    modifier = Modifier.width(timestampMetrics.totalWidth + TERMINAL_LEAD_SLOT_WIDTH + CliSpacing.xs)
                )
            },
            style = cliTerminalFootnoteStyle(),
        )
    }
}

@Composable
private fun CliTerminalProgressStepRow(
    progress: CliTerminalProgress,
    leading: @Composable () -> Unit,
    style: androidx.compose.ui.text.TextStyle,
) {
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
        leading()
        if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
            CliShimmerText(
                text = progress.text,
                style = style,
                baseColor = cliLineToneColor(progress.tone),
                maxLines = BODY_MAX_LINES,
            )
        } else {
            Text(
                text = PROGRESS_SPINNER_FRAMES[frame],
                style = style,
                color = cliLineToneColor(progress.tone),
                maxLines = 1,
                modifier = Modifier.width(PROGRESS_SPINNER_WIDTH),
            )
            Text(
                text = progress.text,
                style = style,
                color = cliLineToneColor(progress.tone),
                maxLines = BODY_MAX_LINES,
            )
        }
    }
}

@Composable
private fun CliTerminalScrollEffects(
    terminal: CliTerminalState,
    listState: LazyListState,
    visibleProgress: CliTerminalProgress?,
    followsOutput: Boolean,
    onFollowsOutputChanged: (Boolean) -> Unit,
    outputLayoutRevision: Int,
) {
    val density = LocalDensity.current
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
            withFrameNanos { }
            val layout = listState.layoutInfo
            val anchorHeightPx = with(density) { TERMINAL_OUTPUT_BOTTOM_GAP.roundToPx() }
            listState.scrollToItem(
                index = lastIndex,
                scrollOffset = terminalBottomScrollOffset(
                    viewportStartOffset = layout.viewportStartOffset,
                    viewportEndOffset = layout.viewportEndOffset,
                    anchorHeightPx = anchorHeightPx,
                ),
            )
        }
    }
    LaunchedEffect(terminal.blockPending, terminal.promptText) {
        if (terminal.promptText != null) return@LaunchedEffect
        while (terminal.drainBlockRow()) {
            delay(BLOCK_ROW_STEP_MS)
        }
    }
    LaunchedEffect(terminal.lines.lastOrNull()?.id) {
        if (terminal.lines.lastOrNull()?.prompt == true) {
            onFollowsOutputChanged(true)
        }
    }
    LaunchedEffect(visibleProgress?.id, visibleProgress?.text) {
        val live = visibleProgress ?: return@LaunchedEffect
        delay(CONNECTION_PROGRESS_WATCHDOG_MS)
        terminal.expireProgress(live.id)
    }
}

private const val TIMESTAMP_SAMPLE = "00:00:00"

private data class CliTerminalTimestampMetrics(
    val bracketSlotWidth: Dp,
    val timeSlotWidth: Dp,
    val totalWidth: Dp,
)

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
private val TERMINAL_PROMPT_VISUAL_OFFSET_MODERN = 7.dp
private val TERMINAL_PROMPT_MARKER_OFFSET_MODERN = 1.dp
private val TERMINAL_PROMPT_CURSOR_OFFSET_MODERN = 1.dp
private val PROGRESS_SPINNER_FRAMES = arrayOf("|", "/", "—", "\\")
private val TERMINAL_LEAD_SLOT_WIDTH = 12.dp
private const val TERMINAL_FOOTNOTE_FONT_SP = 11f
private const val TERMINAL_FOOTNOTE_LINE_SP = 14f
private const val FRESH_PLACED_ROW_MS = 2_000L

private const val KEY_COLUMN_WEIGHT = 1f
private const val VALUE_COLUMN_WEIGHT = 1.4f
private const val FLAGGED_KEY_COLUMN_WEIGHT = 0.65f
private const val FLAGGED_VALUE_COLUMN_WEIGHT = 1.75f

@Composable
private fun CliPromptRow(
    terminal: CliTerminalState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val prompt = terminal.promptText
    val typedCount = terminal.promptTypedCount
    val plainStyle = LocalCliVisualStyle.current == VisualStyle.PLAIN
    LaunchedEffect(prompt, plainStyle) {
        if (prompt != null) {
            if (plainStyle) {
                val startChars = terminal.promptTypedCount
                val startNanos = withFrameNanos { it }
                while (terminal.promptTypedCount < prompt.length) {
                    withFrameNanos { now ->
                        val elapsedMs = (now - startNanos) / 1_000_000L
                        terminal.promptTypedCount =
                            (startChars + (elapsedMs / PROMPT_TYPE_STEP_PLAIN_MS).toInt())
                                .coerceAtMost(prompt.length)
                    }
                }
                kotlinx.coroutines.delay(PROMPT_COMMIT_HOLD_PLAIN_MS)
            } else {
                while (terminal.promptTypedCount < prompt.length) {
                    kotlinx.coroutines.delay(40L)
                    terminal.promptTypedCount++
                }
                kotlinx.coroutines.delay(260L)
            }
            terminal.commitPrompt()
        }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = "fhg ", style = CliType.body, color = colors.accent)
        Text(
            text = ">",
            style = CliType.body,
            color = colors.accent,
            modifier = Modifier.offset(
                y = if (plainStyle) TERMINAL_PROMPT_MARKER_OFFSET_MODERN else 0.dp,
            ),
        )
        Text(text = " ", style = CliType.body, color = colors.accent)
        if (prompt != null) {
            Text(
                text = prompt.take(typedCount),
                style = CliType.body,
                color = colors.fg,
                maxLines = 1,
            )
        }
        CliBlinkingCursor(
            modifier = Modifier.offset(
                y = if (plainStyle) TERMINAL_PROMPT_CURSOR_OFFSET_MODERN else 0.dp,
            ),
        )
    }
}

@Composable
@Suppress("LongMethod")
private fun CliTerminalHeader(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    onHelpRequested: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CliFoxHero(size = HOME_HEADER_FOX_SIZE)
        Spacer(modifier = Modifier.width(CliSpacing.xs))
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val availableHeaderWidth = maxWidth
            val brand = CliType.display.copy(fontSize = cliScaledSp(20f), lineHeight = cliScaledSp(22f))
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
                val baseStatusStyle = CliType.body.copy(
                    fontSize = STATUS_FONT_SIZE,
                    lineHeight = STATUS_LINE_HEIGHT,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.None,
                    ),
                )
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
                    fontSize = STATUS_FONT_SIZE * statusScale,
                    lineHeight = STATUS_LINE_HEIGHT * statusScale,
                )
                if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
                    if (word in TRANSITION_STATUS_WORDS) {
                        CliShimmerText(
                            text = line.text,
                            style = statusStyle,
                            baseColor = statusColor,
                            maxLines = STATUS_WORD_MAX_LINES,
                        )
                    } else {
                        Text(
                            text = line,
                            style = statusStyle,
                            maxLines = STATUS_WORD_MAX_LINES,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CliStatusDot(
                            color = dotColor,
                            fontSize = statusStyle.fontSize,
                            pulsing = false,
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
        CliHeaderHelpButton(
            contentDescription = stringResource(R.string.cli_help_start_title),
            topBar = true,
            onClick = onHelpRequested,
        )
    }
}

private var brandHeaderTypedOnce = false

@Composable
private fun CliBrandTitle(
    oneLine: Boolean,
    style: androidx.compose.ui.text.TextStyle,
) {
    val colors = LocalCliColors.current
    val instant = brandHeaderTypedOnce
    if (oneLine) {
        Row(verticalAlignment = Alignment.Top) {
            CliTypewriterText(text = "FoxHole ", style = style, color = colors.accent, instant = instant)
            CliTypewriterText(
                text = "Guard",
                style = style,
                color = colors.info,
                instant = instant,
                onFullyTyped = { brandHeaderTypedOnce = true },
            )
            CliBadge(text = stringResource(R.string.cli_badge_beta), color = colors.info)
        }
    } else {
        Column {
            CliTypewriterText(text = "FoxHole", style = style, color = colors.accent, instant = instant)
            Row(verticalAlignment = Alignment.Top) {
                CliTypewriterText(
                    text = "Guard",
                    style = style,
                    color = colors.info,
                    instant = instant,
                    onFullyTyped = { brandHeaderTypedOnce = true },
                )
                CliBadge(text = stringResource(R.string.cli_badge_beta), color = colors.info)
            }
        }
    }
}

@Composable
internal fun cliStatusWordText(word: CliStatusWord): String = when (word) {
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

/** Prompt and accent lines stay bare; narration and state events get class-specific glyphs. */
internal fun cliLineToneIcon(tone: CliLineTone, prompt: Boolean): Int? {
    if (prompt) return null
    return when (tone) {
        CliLineTone.PLAIN, CliLineTone.DIM -> R.drawable.pix_arrow_right
        CliLineTone.ACCENT -> null
        CliLineTone.OK -> R.drawable.pix_check
        CliLineTone.WARN -> R.drawable.pix_clock
        CliLineTone.ERR -> R.drawable.pix_cross
        CliLineTone.INFO -> R.drawable.pix_info
        CliLineTone.VPN -> R.drawable.pix_shield
        CliLineTone.TOR -> R.drawable.pix_tor
        CliLineTone.I2P -> R.drawable.pix_incognito
        CliLineTone.FIREWALL -> R.drawable.pix_fire
        CliLineTone.DNS_FILTER -> R.drawable.pix_dns
    }
}

internal fun cliTerminalLineIcon(line: CliTerminalLine): Int? =
    if (isCliWelcomeLine(line.text)) R.drawable.ic_qs_tile else cliLineToneIcon(line.tone, line.prompt)

@Composable
private fun rememberTypedCharCount(
    lineId: Long,
    length: Int,
    typing: Boolean,
): Int {
    var visibleChars by remember(lineId, length) { mutableIntStateOf(if (typing) 0 else length) }
    LaunchedEffect(lineId, length) {
        if (!typing || visibleChars >= length) return@LaunchedEffect
        val startChars = visibleChars
        val startNanos = withFrameNanos { it }
        while (visibleChars < length) {
            withFrameNanos { now ->
                val elapsedMs = (now - startNanos) / 1_000_000L
                visibleChars = (startChars + (elapsedMs / 10L).toInt() * 2).coerceAtMost(length)
            }
        }
    }
    return visibleChars
}

@Composable
private fun CliTerminalLineRow(
    line: CliTerminalLine,
    isLast: Boolean,
    timestampMetrics: CliTerminalTimestampMetrics,
    terminal: CliTerminalState,
    onOutputHeightChanged: () -> Unit,
) {
    val toneColor = cliLineToneColor(line.tone)
    val body = if (line.prompt) line.text.removePrefix("> ") else line.text
    val typing = remember(line.id) { isLast && line.typed && terminal.claimTyping(line.id) }
    val visibleChars = rememberTypedCharCount(line.id, body.length, typing)
    var measuredHeightPx by remember(line.id) { mutableIntStateOf(0) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cliPlacedRowEntrance(line)
            .onSizeChanged { size ->
                if (isLast && size.height != measuredHeightPx) {
                    measuredHeightPx = size.height
                    onOutputHeightChanged()
                } else {
                    measuredHeightPx = size.height
                }
            },
    ) {
        if (line.footnote) {
            CliTerminalFootnoteBody(line = line, timestampMetrics = timestampMetrics)
            return@Row
        }
        CliTerminalTimestamp(line.timestampMs, timestampMetrics)
        CliTerminalLeadSlot(
            icon = cliTerminalLineIcon(line),
            tint = toneColor,
            promptMarker = line.prompt,
        )
        if (line.inlineValue && line.hasTerminalValueContent) {
            CliTerminalInlineValueRow(
                key = body.take(visibleChars),
                keyColor = toneColor,
                line = line,
                valueVisible = visibleChars >= body.length,
            )
        } else if (line.value == null && line.packages.isEmpty()) {
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
                valueVisible = visibleChars >= body.length,
            )
        }
    }
}

private val CliTerminalLine.hasTerminalValueContent: Boolean
    get() = value != null || packages.isNotEmpty() || flagCountry != null

@Composable
private fun RowScope.CliTerminalInlineValueRow(
    key: String,
    keyColor: Color,
    line: CliTerminalLine,
    valueVisible: Boolean,
) {
    val country = line.flagCountry?.takeIf { cliFlagCode(it) != null }
    val appSize = with(LocalDensity.current) { CliType.small.fontSize.toDp() }
    val appPlaceholder = with(LocalDensity.current) {
        Placeholder(
            width = appSize.toSp(),
            height = appSize.toSp(),
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
        )
    }
    val flagSlot = cliFlagIconSize(CliType.small)
    val flagPlaceholder = with(LocalDensity.current) {
        Placeholder(
            width = flagSlot.width.toSp(),
            height = flagSlot.height.toSp(),
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
        )
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = keyColor)) {
            append(cliTerminalKeyLabel(key))
        }
        if (valueVisible) {
            line.packages.forEachIndexed { index, _ ->
                append(' ')
                appendInlineContent("$INLINE_PACKAGE_SLOT_PREFIX$index", INLINE_CONTENT_ALTERNATE)
            }
            line.value?.let { value ->
                append(' ')
                withStyle(SpanStyle(color = cliLineToneColor(line.valueTone))) {
                    append(value)
                }
            }
            if (country != null) {
                append(' ')
                appendInlineContent(INLINE_FLAG_SLOT, INLINE_CONTENT_ALTERNATE)
            }
        }
    }
    val inlineContent = buildMap {
        line.packages.forEachIndexed { index, packageName ->
            put(
                "$INLINE_PACKAGE_SLOT_PREFIX$index",
                InlineTextContent(appPlaceholder) {
                    CliTerminalAppIcon(packageName = packageName)
                },
            )
        }
        if (country != null) {
            put(
                INLINE_FLAG_SLOT,
                InlineTextContent(flagPlaceholder) {
                    CliFlagIcon(countryCode = country, style = CliType.small)
                },
            )
        }
    }
    Text(
        text = text,
        inlineContent = inlineContent,
        style = CliType.small,
        color = keyColor,
        maxLines = BODY_MAX_LINES,
        softWrap = true,
        textAlign = TextAlign.Start,
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun CliTerminalLeadSlot(
    icon: Int?,
    tint: Color,
    promptMarker: Boolean = false,
) {
    val colors = LocalCliColors.current
    val modernStyle = LocalCliVisualStyle.current == VisualStyle.PLAIN
    val firstLineOffset = if (modernStyle && !promptMarker) 2.dp else 0.dp
    Box(
        modifier = Modifier
            .width(TERMINAL_LEAD_SLOT_WIDTH)
            .padding(top = firstLineOffset),
        contentAlignment = Alignment.TopStart,
    ) {
        when {
            promptMarker -> Text(
                text = ">",
                style = CliType.small,
                color = colors.accent,
                maxLines = 1,
            )
            icon != null -> CliPixIcon(
                id = icon,
                contentDescription = null,
                size = 12.dp,
                tint = tint,
            )
        }
    }
    Spacer(modifier = Modifier.width(CliSpacing.xs))
}

@Composable
private fun RowScope.CliTerminalFootnoteBody(
    line: CliTerminalLine,
    timestampMetrics: CliTerminalTimestampMetrics,
) {
    val colors = LocalCliColors.current
    val style = cliTerminalFootnoteStyle()
    val hasValue = line.value != null || line.flagCountry != null
    Spacer(modifier = Modifier.width(timestampMetrics.totalWidth + TERMINAL_LEAD_SLOT_WIDTH + CliSpacing.xs))
    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (hasValue) cliTerminalKeyLabel(line.text) else line.text,
            style = style,
            color = if (line.tone == CliLineTone.DIM) colors.faint else cliLineToneColor(line.tone),
            maxLines = BODY_MAX_LINES,
            modifier = Modifier.weight(KEY_COLUMN_WEIGHT),
        )
        if (hasValue) {
            Row(
                modifier = Modifier
                    .weight(VALUE_COLUMN_WEIGHT)
                    .padding(start = CliSpacing.sm),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (line.value != null) {
                    Text(
                        text = line.value,
                        style = style,
                        color = cliLineToneColor(line.valueTone),
                        maxLines = BODY_MAX_LINES,
                        textAlign = TextAlign.End,
                    )
                }
                if (line.flagCountry != null) {
                    Spacer(modifier = Modifier.width(CliSpacing.xs))
                    CliFlagIcon(countryCode = line.flagCountry, style = style)
                }
            }
        }
    }
}

@Composable
@ReadOnlyComposable
private fun cliTerminalFootnoteStyle() = CliType.small.copy(
    fontSize = cliScaledSp(TERMINAL_FOOTNOTE_FONT_SP),
    lineHeight = cliScaledSp(TERMINAL_FOOTNOTE_LINE_SP),
)

@Composable
private fun Modifier.cliPlacedRowEntrance(line: CliTerminalLine): Modifier {
    val plainStyle = LocalCliVisualStyle.current == VisualStyle.PLAIN
    val animate = remember(line.id) {
        plainStyle && !line.typed && !line.prompt &&
            System.currentTimeMillis() - line.timestampMs < FRESH_PLACED_ROW_MS
    }
    if (!animate) return this
    val entrance = remember(line.id) { Animatable(0f) }
    LaunchedEffect(line.id) {
        entrance.animateTo(1f, CliMotion.enter(CliMotion.DurationMedium))
    }
    return graphicsLayer { alpha = entrance.value }
}

@Composable
private fun RowScope.CliTerminalKeyValueColumns(
    key: String,
    keyColor: Color,
    line: CliTerminalLine,
    valueVisible: Boolean,
) {
    val flaggedValue = line.flagCountry?.let(::cliFlagCode) != null
    val keyColumnWeight = if (flaggedValue) FLAGGED_KEY_COLUMN_WEIGHT else KEY_COLUMN_WEIGHT
    val valueColumnWeight = if (flaggedValue) FLAGGED_VALUE_COLUMN_WEIGHT else VALUE_COLUMN_WEIGHT
    Text(
        text = cliTerminalKeyLabel(key),
        style = CliType.small,
        color = keyColor,
        maxLines = BODY_MAX_LINES,
        modifier = Modifier.weight(keyColumnWeight),
    )
    Row(
        modifier = Modifier
            .weight(valueColumnWeight)
            .padding(start = CliSpacing.sm),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (valueVisible) {
            line.packages.forEachIndexed { index, packageName ->
                CliTerminalAppIcon(packageName = packageName)
                if (
                    index < line.packages.lastIndex ||
                    line.value != null ||
                    line.flagCountry != null
                ) {
                    Spacer(modifier = Modifier.width(CliSpacing.xs))
                }
            }
            if (line.value != null) {
                CliTerminalValueText(line = line, value = line.value)
            } else if (line.flagCountry != null) {
                CliFlagIcon(countryCode = line.flagCountry, style = CliType.small)
            }
        }
    }
}

@Composable
private fun RowScope.CliTerminalValueText(line: CliTerminalLine, value: String) {
    val country = line.flagCountry?.takeIf { cliFlagCode(it) != null }
    if (country == null) {
        Text(
            text = value,
            style = CliType.small,
            color = cliLineToneColor(line.valueTone),
            maxLines = BODY_MAX_LINES,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
        return
    }
    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value,
            style = CliType.small,
            color = cliLineToneColor(line.valueTone),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(CliSpacing.xs))
        CliFlagIcon(countryCode = country, style = CliType.small)
    }
}

internal fun cliTerminalKeyLabel(key: String): String {
    val trimmed = key.trimEnd()
    return if (trimmed.endsWith(':')) trimmed else "$trimmed:"
}

private const val INLINE_PACKAGE_SLOT_PREFIX = "cli_terminal_status_package_"
private const val INLINE_FLAG_SLOT = "cli_terminal_status_flag"
private const val INLINE_CONTENT_ALTERNATE = "�"

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

private const val BODY_MAX_LINES = 4
private const val TERMINAL_BOTTOM_PROXIMITY_ROWS = 1

private const val STATUS_WORD_MAX_LINES = 1

private val TRANSITION_STATUS_WORDS = setOf(
    CliStatusWord.CONNECTING,
    CliStatusWord.RECONNECTING,
    CliStatusWord.DISCONNECTING,
)

private val STATUS_FONT_SIZE = cliScaledSp(13f)

private const val PROMPT_TYPE_STEP_PLAIN_MS = 16L
private const val PROMPT_COMMIT_HOLD_PLAIN_MS = 140L

private val STATUS_LINE_HEIGHT = cliScaledSp(16f)
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
private fun CliBlinkingCursor(modifier: Modifier = Modifier) {
    val colors = LocalCliColors.current
    var on by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                kotlinx.coroutines.delay(530L)
                on = !on
            }
        }
    }
    Canvas(modifier = modifier.size(width = 7.dp, height = 13.dp)) {
        drawRect(color = if (on) colors.fg else Color.Transparent)
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
