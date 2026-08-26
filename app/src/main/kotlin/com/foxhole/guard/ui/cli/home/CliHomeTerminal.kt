package com.foxhole.guard.ui.cli.home

import androidx.compose.animation.AnimatedContent
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
import com.foxhole.core.model.networkUp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliMotion
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliBootstrapFade
import com.foxhole.guard.ui.cli.cliDisplayStyle
import com.foxhole.guard.ui.cli.cliScaledSp
import com.foxhole.guard.ui.cli.cliTypography
import com.foxhole.guard.ui.cli.components.CliBadge
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliHeaderHelpButton
import com.foxhole.guard.ui.cli.components.CliHomeSectionGap
import com.foxhole.guard.ui.cli.components.CliIcon
import com.foxhole.guard.ui.cli.components.CliLatencyKind
import com.foxhole.guard.ui.cli.components.CliLatencyTone
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliSemanticGlyph
import com.foxhole.guard.ui.cli.components.CliShimmerText
import com.foxhole.guard.ui.cli.components.CliTypewriterText
import com.foxhole.guard.ui.cli.components.cliFlagCode
import com.foxhole.guard.ui.cli.components.cliFlagIconSize
import com.foxhole.guard.ui.cli.components.cliLatencyTone
import com.foxhole.guard.ui.cli.components.cliSemanticIcon
import com.foxhole.guard.ui.cli.components.cliSystemMotionEnabled
import com.foxhole.guard.ui.cli.fox.CliFoxHero
import com.foxhole.guard.ui.cli.settings.rememberCliAppIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
@Suppress("LongParameterList")
internal fun CliTerminalPanel(
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    listState: LazyListState,
    followsOutput: Boolean,
    onFollowsOutputChanged: (Boolean) -> Unit,
    onInteraction: () -> Unit,
    onClearRequested: () -> Unit,
    onHelpRequested: () -> Unit,
    contentAfterHeader: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val timestampMetrics = rememberCliTerminalTimestampMetrics()
    Column(modifier = modifier) {
        CliTerminalHeader(home = home, onHelpRequested = onHelpRequested)
        CliHomeSectionGap()
        contentAfterHeader()
        CliHomeSectionTypography {
            CliPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(CLI_HOME_TERMINAL_TAG),
                title = stringResource(R.string.cli_home_section_console),
                titleModifier = Modifier.cliHomeSectionHeaderPlacement(),
                titleColor = colors.accent,
                icon = R.drawable.lin_terminal,
                onClick = onInteraction,
                onLongClick = {
                    onInteraction()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClearRequested()
                },
            ) {
                var outputLayoutRevision by remember { mutableIntStateOf(0) }
                val viewportHeight = remember(listState) { intArrayOf(-1) }
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
                        .weight(1f)
                        .onSizeChanged { size ->
                            val viewportChanged =
                                size.height > 0 && size.height != viewportHeight[0]
                            if (
                                viewportChanged &&
                                followsOutput &&
                                !listState.isScrollInProgress
                            ) {
                                viewportHeight[0] = size.height
                                val bottomIndex = terminalOutputBottomIndex(
                                    lineCount = terminal.lines.size,
                                    hasVisibleProgress = visibleProgress != null,
                                )
                                val anchorHeightPx = with(density) {
                                    TERMINAL_OUTPUT_BOTTOM_GAP.roundToPx()
                                }
                                listState.requestScrollToItem(
                                    index = bottomIndex,
                                    scrollOffset = terminalBottomScrollOffset(
                                        viewportStartOffset = 0,
                                        viewportEndOffset = size.height,
                                        anchorHeightPx = anchorHeightPx,
                                    ),
                                )
                            } else {
                                viewportHeight[0] = size.height
                            }
                        },
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
}

@Composable
@ReadOnlyComposable
private fun cliTerminalPromptOffset(): Dp =
    TERMINAL_PROMPT_VISUAL_OFFSET

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
                text = cliTerminalLineText(title),
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
            style = CliType.small,
        )
    }
}

@Composable
private fun CliTerminalProgressStepRow(
    progress: CliTerminalProgress,
    leading: @Composable () -> Unit,
    style: androidx.compose.ui.text.TextStyle,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        leading()
        val color = cliLineToneColor(progress.tone)
        if (progress.animated) {
            CliShimmerText(
                text = cliTerminalLineText(progress.text),
                style = style,
                baseColor = color,
                maxLines = BODY_MAX_LINES,
            )
        } else {
            Text(
                text = cliTerminalLineText(progress.text),
                style = style,
                color = color,
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
    var programmaticScrollInProgress by remember(listState) { mutableStateOf(false) }
    LaunchedEffect(terminal.promptText) {
        if (terminal.promptText != null) {
            onFollowsOutputChanged(true)
        }
    }
    LaunchedEffect(listState, terminal, visibleProgress?.id) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress && !programmaticScrollInProgress,
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                terminalOutputBottomIndex(
                    lineCount = terminal.lines.size,
                    hasVisibleProgress = visibleProgress != null,
                ),
            )
        }.collect { (userScrolling, lastVisibleIndex, lastIndex) ->
            if (userScrolling) {
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
        if (!followsOutput) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .first { scrolling -> !scrolling }
        withFrameNanos { }
        val lastIndex = terminalOutputBottomIndex(
            lineCount = terminal.lines.size,
            hasVisibleProgress = visibleProgress != null,
        )
        if (lastIndex >= 0) {
            val layout = listState.layoutInfo
            val anchorHeightPx = with(density) { TERMINAL_OUTPUT_BOTTOM_GAP.roundToPx() }
            programmaticScrollInProgress = true
            try {
                listState.scrollToItem(
                    index = lastIndex,
                    scrollOffset = terminalBottomScrollOffset(
                        viewportStartOffset = layout.viewportStartOffset,
                        viewportEndOffset = layout.viewportEndOffset,
                        anchorHeightPx = anchorHeightPx,
                    ),
                )
            } finally {
                programmaticScrollInProgress = false
            }
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
private const val CONNECTION_PROGRESS_WATCHDOG_MS = 45_000L
private val TIMESTAMP_INNER_GAP = 2.dp
private const val TIMESTAMP_BRACKET_LIGHTEN = 0.35f
private val TERMINAL_OUTPUT_BOTTOM_GAP = 24.dp
private val TERMINAL_PROMPT_VISUAL_OFFSET = 7.dp
private val TERMINAL_LEAD_SLOT_WIDTH = 12.dp
private const val FRESH_PLACED_ROW_MS = 2_000L

private const val KEY_COLUMN_WEIGHT = 1f
private const val VALUE_COLUMN_WEIGHT = 1.4f
private const val FLAGGED_KEY_COLUMN_WEIGHT = 0.5f
private const val FLAGGED_VALUE_COLUMN_WEIGHT = 1.9f

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
            if (!cliSystemMotionEnabled()) {
                terminal.promptTypedCount = prompt.length
                terminal.commitPrompt()
                return@LaunchedEffect
            }
            val startChars = terminal.promptTypedCount
            val startNanos = withFrameNanos { it }
            while (terminal.promptTypedCount < prompt.length) {
                withFrameNanos { now ->
                    val elapsedMs = (now - startNanos) / 1_000_000L
                    terminal.promptTypedCount =
                        (startChars + (elapsedMs / PROMPT_TYPE_STEP_MS).toInt())
                            .coerceAtMost(prompt.length)
                }
            }
            kotlinx.coroutines.delay(PROMPT_COMMIT_HOLD_MS)
            terminal.commitPrompt()
        }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = cliTerminalCommandText(stringResource(R.string.cli_home_terminal_prompt)) + " ",
            style = CliType.body,
            color = colors.accent,
            modifier = Modifier.alignByBaseline(),
        )
        if (prompt != null) {
            Text(
                text = cliTerminalCommandText(prompt.take(typedCount)),
                style = CliType.body,
                color = colors.fg,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
        CliBlinkingCursor(
            modifier = Modifier.alignBy { measured -> measured.measuredHeight }
                .offset(y = CLI_TERMINAL_CURSOR_VERTICAL_OFFSET),
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
            val brandFitsOneLine = maxWidth >= 224.dp
            Column {
                CliBrandTitle(
                    oneLine = brandFitsOneLine,
                    style = cliDisplayStyle("FOXHOLE GUARD").copy(
                        fontSize = cliScaledSp(20f),
                        lineHeight = cliScaledSp(22f),
                    ),
                )
                Spacer(modifier = Modifier.height(HOME_HEADER_STATUS_GAP))
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
                val baseStatusStyle = cliTypography(pixelArtEnabled = false).button.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.None,
                    ),
                )
                val line = buildAnnotatedString {
                    if (!connected) {
                        withStyle(SpanStyle(color = statusColor)) {
                            append(cliStatusWordText(word))
                        }
                    } else {
                        withStyle(SpanStyle(color = colors.info)) {
                            append(stringResource(R.string.cli_home_status_connected_prefix))
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
                            withStyle(SpanStyle(color = color)) { append(label) }
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
                    cliStatusScale(
                        availableWidthPx = availableHeaderWidth.toPx(),
                        textWidthPx = preferredTextWidth,
                    )
                }
                val statusStyle = baseStatusStyle.copy(
                    fontSize = baseStatusStyle.fontSize * statusScale,
                    lineHeight = baseStatusStyle.lineHeight * statusScale,
                )
                val statusReady = home.profilesLoaded && home.settingsHydrated
                AnimatedContent(
                    targetState = statusReady,
                    transitionSpec = { cliBootstrapFade() },
                    modifier = Modifier.fillMaxWidth(),
                    label = "homeHeaderStatusReady",
                ) { ready ->
                    if (!ready) {
                        CliShimmerText(
                            text = cliTerminalLineText(stringResource(R.string.cli_common_loading_data)),
                            style = statusStyle,
                            baseColor = colors.dim,
                            maxLines = STATUS_WORD_MAX_LINES,
                        )
                    } else {
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
                    }
                }
            }
        }
        CliHeaderHelpButton(
            contentDescription = stringResource(R.string.cli_quick_start_title),
            topBar = true,
            alignIconToFirstLine = true,
            firstLineText = "FOXHOLE GUARD",
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
            CliTypewriterText(text = "FOXHOLE", style = style, color = colors.accent, instant = instant)
            Spacer(modifier = Modifier.width(HOME_BRAND_WORD_GAP))
            CliTypewriterText(
                text = "GUARD",
                style = style,
                color = colors.info,
                instant = instant,
                onFullyTyped = { brandHeaderTypedOnce = true },
            )
            CliBadge(text = stringResource(R.string.cli_badge_beta), color = colors.info)
        }
    } else {
        Column {
            CliTypewriterText(text = "FOXHOLE", style = style, color = colors.accent, instant = instant)
            Row(verticalAlignment = Alignment.Top) {
                CliTypewriterText(
                    text = "GUARD",
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
        CliStatusWord.CONNECTING, CliStatusWord.RECONNECTING, CliStatusWord.DISCONNECTING -> colors.alert
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

@Composable
internal fun latencyColor(ms: Long?): Color {
    val colors = LocalCliColors.current
    return when (cliLatencyTone(ms, CliLatencyKind.HOME)) {
        CliLatencyTone.UNAVAILABLE -> Color.Unspecified
        CliLatencyTone.NORMAL -> colors.ok
        CliLatencyTone.DEGRADED -> colors.warn
        CliLatencyTone.ELEVATED -> colors.warn
        CliLatencyTone.POOR -> colors.err
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
        CliLineTone.PENDING -> colors.alert
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

internal fun cliLineToneIcon(tone: CliLineTone, prompt: Boolean): Int? {
    if (prompt) return null
    return when (tone) {
        CliLineTone.PLAIN, CliLineTone.DIM -> R.drawable.lin_arrow_right
        CliLineTone.ACCENT -> null
        CliLineTone.OK -> cliSemanticIcon(CliSemanticGlyph.SUCCESS)
        CliLineTone.PENDING -> cliSemanticIcon(CliSemanticGlyph.PENDING)
        CliLineTone.WARN -> cliSemanticIcon(CliSemanticGlyph.WARNING)
        CliLineTone.ERR -> cliSemanticIcon(CliSemanticGlyph.ERROR)
        CliLineTone.INFO -> cliSemanticIcon(CliSemanticGlyph.INFORMATION)
        CliLineTone.VPN -> R.drawable.lin_shield
        CliLineTone.TOR -> R.drawable.lin_tor
        CliLineTone.I2P -> R.drawable.lin_incognito
        CliLineTone.FIREWALL -> R.drawable.lin_fire
        CliLineTone.DNS_FILTER -> R.drawable.lin_dns
    }
}

private fun cliTerminalExplicitIcon(icon: CliLineIcon?): Int? =
    when (icon) {
        CliLineIcon.IP -> R.drawable.lin_globe
        CliLineIcon.LOCATION -> R.drawable.lin_map
        null -> null
    }

internal fun cliTerminalLineIcon(line: CliTerminalLine): Int? =
    cliTerminalExplicitIcon(line.icon) ?: if (isCliWelcomeLine(line.text)) {
        R.drawable.ic_qs_tile
    } else {
        cliLineToneIcon(line.tone, line.prompt)
    }

internal fun cliTerminalFootnoteIcon(line: CliTerminalLine): Int? =
    cliTerminalExplicitIcon(line.icon)

@Composable
private fun rememberTypedCharCount(
    lineId: Long,
    length: Int,
    typing: Boolean,
): Int {
    var visibleChars by remember(lineId, length) { mutableIntStateOf(if (typing) 0 else length) }
    LaunchedEffect(lineId, length) {
        if (!typing || visibleChars >= length) return@LaunchedEffect
        if (!cliSystemMotionEnabled()) {
            visibleChars = length
            return@LaunchedEffect
        }
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
    val rawBody = if (line.prompt) line.text.removePrefix("> ") else line.text
    val body = if (line.prompt) cliTerminalCommandText(rawBody) else cliTerminalLineText(rawBody)
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
            iconSize = cliTerminalLeadIconSize(line),
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
    iconSize: Dp = CLI_HOME_TERMINAL_LEAD_ICON_SIZE,
) {
    val colors = LocalCliColors.current
    val firstLineHeight = with(LocalDensity.current) { CliType.small.lineHeight.toDp() }
    Box(
        modifier = Modifier
            .width(TERMINAL_LEAD_SLOT_WIDTH)
            .height(firstLineHeight),
        contentAlignment = Alignment.Center,
    ) {
        when {
            promptMarker -> Text(
                text = ">",
                style = CliType.small,
                color = colors.accent,
                maxLines = 1,
            )
            icon != null -> CliIcon(
                id = icon,
                contentDescription = null,
                size = iconSize,
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
    val style = CliType.small
    val hasValue = line.value != null || line.flagCountry != null
    Spacer(modifier = Modifier.width(timestampMetrics.totalWidth))
    CliTerminalLeadSlot(
        icon = cliTerminalFootnoteIcon(line),
        tint = cliLineToneColor(line.valueTone),
    )
    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (hasValue) cliTerminalKeyLabel(line.text) else cliTerminalLineText(line.text),
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
private fun Modifier.cliPlacedRowEntrance(line: CliTerminalLine): Modifier {
    val animate = remember(line.id) {
        !line.typed && !line.prompt &&
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
        val keepOnOneLine = cliTerminalValueStaysOnOneLine(line.icon, value)
        Text(
            text = value,
            style = CliType.small,
            color = cliLineToneColor(line.valueTone),
            maxLines = if (keepOnOneLine) 1 else BODY_MAX_LINES,
            softWrap = !keepOnOneLine,
            overflow = if (keepOnOneLine) TextOverflow.Ellipsis else TextOverflow.Clip,
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

internal fun cliTerminalValueStaysOnOneLine(icon: CliLineIcon?, value: String): Boolean {
    if (icon == CliLineIcon.IP) return true
    val firstSegment = value.substringBefore('·').trim()
    val ipv4 = firstSegment.count { it == '.' } == 3 &&
        firstSegment.all { character -> character.isDigit() || character == '.' }
    val ipv6 = ':' in firstSegment && firstSegment.all { character ->
        character.isDigit() || character.lowercaseChar() in 'a'..'f' || character == ':' || character == '.'
    }
    return ipv4 || ipv6
}

internal fun cliTerminalKeyLabel(key: String): String {
    val trimmed = cliTerminalLineText(key.trimEnd())
    return if (trimmed.endsWith(':')) trimmed else "$trimmed:"
}

internal fun cliTerminalLineText(text: String): String {
    if (isCliWelcomeLine(text)) return text
    val tokenStart = text.indexOfFirst(Char::isLetter)
    if (tokenStart < 0) return text
    val tokenEnd = text.indexOfFirstFrom(tokenStart) { character -> !character.isLetterOrDigit() }
        .takeIf { it >= 0 }
        ?: text.length
    val lead = text.substring(tokenStart, tokenEnd)
    if (lead in CLI_TERMINAL_UPPERCASE_TOKENS) return text
    val loweredLead = lead.lowercase()
    if (lead == loweredLead) return text
    return buildString(text.length) {
        append(text, 0, tokenStart)
        append(loweredLead)
        append(text, tokenEnd, text.length)
    }
}

private inline fun String.indexOfFirstFrom(startIndex: Int, predicate: (Char) -> Boolean): Int {
    for (index in startIndex until length) {
        if (predicate(this[index])) return index
    }
    return -1
}

private val CLI_TERMINAL_UPPERCASE_TOKENS = setOf("VPN", "TOR", "I2P", "DNS", "IP")

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

internal val CLI_HOME_TERMINAL_LEAD_ICON_SIZE = 12.dp

internal val CLI_HOME_TERMINAL_WELCOME_ICON_SIZE = 14.dp

internal fun cliTerminalLeadIconSize(line: CliTerminalLine): Dp =
    if (isCliWelcomeLine(line.text)) {
        CLI_HOME_TERMINAL_WELCOME_ICON_SIZE
    } else {
        CLI_HOME_TERMINAL_LEAD_ICON_SIZE
    }

private const val STATUS_WORD_MAX_LINES = 1

private val TRANSITION_STATUS_WORDS = setOf(
    CliStatusWord.CONNECTING,
    CliStatusWord.RECONNECTING,
    CliStatusWord.DISCONNECTING,
)

private const val PROMPT_TYPE_STEP_MS = 24L
private const val PROMPT_COMMIT_HOLD_MS = 160L

private val HOME_BRAND_WORD_GAP = 2.dp
private val HOME_HEADER_STATUS_GAP = 2.dp
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
    val cursorHeight = with(LocalDensity.current) {
        (CliType.body.fontSize * CLI_TERMINAL_CURSOR_CAP_HEIGHT_RATIO).toDp()
    }
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
    Canvas(
        modifier = modifier.size(
            width = cursorHeight * CLI_TERMINAL_CURSOR_WIDTH_RATIO,
            height = cursorHeight,
        ),
    ) {
        drawRect(color = if (on) colors.fg else Color.Transparent)
    }
}

internal const val CLI_TERMINAL_CURSOR_CAP_HEIGHT_RATIO = 0.72f
internal const val CLI_TERMINAL_CURSOR_WIDTH_RATIO = 0.5f
internal val CLI_TERMINAL_CURSOR_VERTICAL_OFFSET = 1.dp

@Composable
internal fun stateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.IDLE -> stringResource(R.string.cli_home_state_offline)
    ConnectionState.CONNECTING -> stringResource(R.string.cli_home_state_connecting)
    ConnectionState.CONNECTED -> stringResource(R.string.cli_home_state_connected)
    ConnectionState.RECONNECTING -> stringResource(R.string.cli_home_state_reconnecting)
    ConnectionState.DISCONNECTING -> stringResource(R.string.cli_home_state_disconnecting)
    ConnectionState.ERROR -> stringResource(R.string.cli_home_state_error)
}
