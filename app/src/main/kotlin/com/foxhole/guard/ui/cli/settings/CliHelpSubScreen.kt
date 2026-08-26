package com.foxhole.guard.ui.cli.settings

import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliRadius
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.cliCaptionSpanStyle
import com.foxhole.guard.ui.cli.cliCaptionTextStyle
import com.foxhole.guard.ui.cli.cliHeadingText
import com.foxhole.guard.ui.cli.cliRowTextStyle
import com.foxhole.guard.ui.cli.cliScaledSp
import com.foxhole.guard.ui.cli.cliVerticalEnter
import com.foxhole.guard.ui.cli.cliVerticalExit
import com.foxhole.guard.ui.cli.components.CLI_MENU_ROW_MIN_HEIGHT
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliIcon
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliStringSetSaver
import com.foxhole.guard.ui.cli.components.CliSwatchDot
import com.foxhole.guard.ui.cli.components.cliPanelBackground
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.cli.onboarding.CliQuickStartItems

@Composable
internal fun CliHelpSubScreen(
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    var expandedKeys by rememberSaveable(stateSaver = CliStringSetSaver) {
        mutableStateOf(emptySet<String>())
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_cfg_more_help),
            iconGlyph = "?",
            iconColor = colors.info,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),
        ) {
            HELP_SECTIONS.forEach { section ->
                CliHelpCard(
                    section = section,
                    expanded = section.key in expandedKeys,
                    onToggle = {
                        expandedKeys = if (section.key in expandedKeys) {
                            expandedKeys - section.key
                        } else {
                            expandedKeys + section.key
                        }
                    },
                )
                Spacer(modifier = Modifier.height(CliSpacing.sm))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CliHelpCard(
    section: CliHelpSection,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalCliColors.current
    val appearance = LocalCliPanelAppearance.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(CliRadius.panel)
    val frameWidth = 1.dp
    val cardBackground = cliPanelBackground(Color.Unspecified, colors.panel, appearance)
    val titleBackground = cliPanelBackground(Color.Unspecified, colors.panelAlt, appearance)
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(cardBackground)
                .border(frameWidth, if (expanded) colors.accentDim else colors.border, shape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = CLI_MENU_ROW_MIN_HEIGHT)
                    .background(titleBackground)
                    .cliPressable(onClick = onToggle)
                    .padding(horizontal = CliSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CliHelpTopicIcon(section.icon)
                Spacer(modifier = Modifier.width(CliSpacing.sm))
                Text(
                    text = buildAnnotatedString {
                        withStyle(cliCaptionSpanStyle(stringResource(section.titleRes))) {
                            append(cliHeadingText(stringResource(section.titleRes)))
                        }
                    },
                    style = cliCaptionTextStyle(),
                    color = if (expanded) colors.fg else colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .weight(1f)
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = HELP_MARQUEE_INITIAL_DELAY_MS,
                            repeatDelayMillis = HELP_MARQUEE_REPEAT_DELAY_MS,
                        ),
                )
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = CliType.body,
                    color = colors.info,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = cliVerticalEnter(),
                exit = cliVerticalExit(),
            ) {
                Column(modifier = Modifier.padding(CliSpacing.md)) {
                    val body = stringResource(section.bodyRes)
                    when (section.key) {
                        HELP_KEY_QUICK_START ->
                            CliQuickStartItems(
                                body = body,
                                framed = false,
                                iconColor = colors.info,
                                smartBody = stringResource(R.string.cli_help_smart_body),
                                detailsBody = stringResource(R.string.cli_help_start_details),
                            )
                        HELP_KEY_SMART -> CliSmartHelpBody(body = body, icon = section.icon)
                        else -> CliHelpBody(body = body, icon = section.icon)
                    }
                    if (section.key == HELP_KEY_KILLSWITCH) {
                        CliActionRow(
                            label = stringResource(R.string.cli_help_killswitch_link),
                            icon = R.drawable.lin_settings,
                            actionColor = colors.info,
                            labelColor = colors.fg,
                            onTap = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CliHelpBody(
    body: String,
    @DrawableRes icon: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
        body.split("\n\n").forEachIndexed { paragraphIndex, paragraph ->
            if (paragraphIndex > 0) {
                CliRowDivider()
            }
            paragraph.lineSequence().filter(String::isNotBlank).forEach { line ->
                CliHelpTextRow(text = line, icon = icon)
            }
        }
    }
}

@Composable
internal fun CliSmartHelpBody(
    body: String,
    @DrawableRes icon: Int,
    framed: Boolean = false,
) {
    val content = remember(body) { smartHelpContent(body) }
    if (content == null) {
        if (framed) {
            CliPanel(modifier = Modifier.fillMaxWidth()) {
                CliHelpBody(body = body, icon = icon)
            }
        } else {
            CliHelpBody(body = body, icon = icon)
        }
        return
    }
    val colors = LocalCliColors.current
    val contentBody: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliHelpTextRow(text = content.intro, icon = icon)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                CliHelpTable(
                    title = content.metricsTitle,
                    rows = content.metrics,
                    alignValuesEnd = false,
                    modifier = Modifier
                        .weight(SMART_HELP_METRIC_TABLE_WEIGHT)
                        .fillMaxHeight(),
                )
                CliHelpTable(
                    title = content.latencyTitle,
                    rows = content.latencies.mapIndexed { index, label ->
                        CliHelpTableRow(
                            value = label,
                            swatch = listOf(colors.ok, colors.warn, colors.alert, colors.err)[index],
                        )
                    },
                    modifier = Modifier
                        .weight(SMART_HELP_LATENCY_TABLE_WEIGHT)
                        .fillMaxHeight(),
                    showTitle = false,
                    alignValuesEnd = false,
                )
            }
            CliHelpTextRow(text = content.outro, icon = icon)
        }
    }
    if (framed) {
        CliPanel(modifier = Modifier.fillMaxWidth(), content = { contentBody() })
    } else {
        contentBody()
    }
}

@Composable
private fun CliHelpTable(
    title: String,
    rows: List<CliHelpTableRow>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    showTitle: Boolean = true,
    alignValuesEnd: Boolean = false,
) {
    val colors = LocalCliColors.current
    val shape = RoundedCornerShape(CliRadius.panel)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.panelAlt)
            .border(1.dp, colors.border, shape),
    ) {
        if (showTitle) {
            Text(
                text = title,
                style = CliType.small,
                color = colors.dim,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SMART_HELP_TABLE_CELL_HEIGHT)
                    .padding(horizontal = CliSpacing.sm, vertical = CliSpacing.xs),
            )
        }
        rows.forEachIndexed { index, row ->
            if (showTitle || index > 0) {
                CliRowDivider()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SMART_HELP_TABLE_CELL_HEIGHT)
                    .padding(horizontal = CliSpacing.sm, vertical = CliSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.swatch != null) {
                    CliSwatchDot(
                        color = row.swatch,
                        size = 10.dp,
                    )
                    Spacer(modifier = Modifier.width(CliSpacing.sm))
                }
                if (row.key != null) {
                    Text(
                        text = row.key,
                        style = CliType.small,
                        color = colors.info,
                        modifier = Modifier.width(52.dp),
                    )
                }
                Text(
                    text = row.value,
                    style = CliType.small,
                    color = colors.fg,
                    textAlign = if (alignValuesEnd) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CliHelpTextRow(
    text: String,
    @DrawableRes icon: Int,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        CliHelpTopicIcon(icon)
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Text(
            text = buildAnnotatedString {
                val term = helpGlossaryTerm(text)
                if (term != null) {
                    withStyle(SpanStyle(color = colors.accent)) { append(term) }
                    append(text.substring(term.length))
                } else {
                    append(text)
                }
            },
            style = cliRowTextStyle().copy(lineHeight = cliScaledSp(22f)),
            color = colors.fg,
            modifier = Modifier.weight(1f),
        )
    }
}

internal data class CliSmartHelpContent(
    val intro: String,
    val metricsTitle: String,
    val metrics: List<CliHelpTableRow>,
    val latencyTitle: String,
    val latencies: List<String>,
    val outro: String,
)

internal data class CliHelpTableRow(
    val key: String? = null,
    val value: String,
    val swatch: Color? = null,
)

internal fun smartHelpContent(body: String): CliSmartHelpContent? {
    val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.size != SMART_HELP_LINE_COUNT) return null
    val labels = listOf(splitHelpLabel(lines[1]), splitHelpLabel(lines[2]))
    if (labels.any { it == null }) return null
    val metrics = requireNotNull(labels[0])
    val latency = requireNotNull(labels[1])
    val metricRows = metrics.second.split(';').mapNotNull(::metricHelpRow)
    val latencyRows = latency.second.split(',').map(String::trim).filter(String::isNotEmpty)
    if (metricRows.size != SMART_HELP_METRIC_COUNT || latencyRows.size != SMART_HELP_LATENCY_COUNT) return null
    return CliSmartHelpContent(
        intro = lines.first(),
        metricsTitle = metrics.first,
        metrics = metricRows,
        latencyTitle = latency.first,
        latencies = latencyRows,
        outro = lines.last(),
    )
}

private fun splitHelpLabel(line: String): Pair<String, String>? {
    val separator = line.indexOf(SMART_HELP_LABEL_SEPARATOR)
        .takeIf { it >= 0 }
        ?: line.indexOf(':')
    if (separator <= 0 || separator == line.lastIndex) return null
    return line.take(separator).trim() to line.substring(separator + 1).trim()
}

private fun metricHelpRow(value: String): CliHelpTableRow? {
    val trimmed = value.trim()
    val separator = trimmed.indexOf(' ')
    if (separator <= 0 || separator == trimmed.lastIndex) return null
    return CliHelpTableRow(
        key = trimmed.take(separator),
        value = trimmed.substring(separator + 1),
    )
}

@Composable
private fun CliHelpTopicIcon(@DrawableRes icon: Int) {
    CliIcon(
        id = icon,
        contentDescription = null,
        size = 16.dp,
        tint = LocalCliColors.current.info,
    )
}

private fun helpGlossaryTerm(line: String): String? {
    val dash = line.indexOf(" — ")
    if (dash <= 0 || dash > HELP_TERM_MAX_CHARS) return null
    val term = line.take(dash)
    if (term.any { it in HELP_TERM_STOP_CHARS }) return null
    return term
}

private const val HELP_TERM_MAX_CHARS = 28

private const val HELP_TERM_STOP_CHARS = ".,:;"

private class CliHelpSection(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    @DrawableRes val icon: Int,
)

private val HELP_SECTIONS = listOf(
    CliHelpSection(
        HELP_KEY_QUICK_START,
        R.string.cli_help_start_title,
        R.string.cli_help_start_body,
        R.drawable.lin_power,
    ),
    CliHelpSection("modes", R.string.cli_help_modes_title, R.string.cli_help_modes_body, R.drawable.lin_shield),
    CliHelpSection("tor", R.string.cli_help_tor_title, R.string.cli_help_tor_body, R.drawable.lin_tor),
    CliHelpSection(HELP_KEY_SMART, R.string.cli_help_smart_title, R.string.cli_help_smart_body, R.drawable.lin_star),
    CliHelpSection("editor", R.string.cli_help_editor_title, R.string.cli_help_editor_body, R.drawable.lin_edit),
    CliHelpSection(
        "transfer",
        R.string.cli_help_transfer_title,
        R.string.cli_help_transfer_body,
        R.drawable.lin_import,
    ),
    CliHelpSection("routing", R.string.cli_help_routing_title, R.string.cli_help_routing_body, R.drawable.lin_link),
    CliHelpSection("dns", R.string.cli_help_dns_title, R.string.cli_help_dns_body, R.drawable.lin_dns),
    CliHelpSection(
        "net_rules",
        R.string.cli_help_net_rules_title,
        R.string.cli_help_net_rules_body,
        R.drawable.lin_globe,
    ),
    CliHelpSection(
        "lan_proxy",
        R.string.cli_help_lan_proxy_title,
        R.string.cli_help_lan_proxy_body,
        R.drawable.lin_device,
    ),
    CliHelpSection("logs", R.string.cli_help_logs_title, R.string.cli_help_logs_body, R.drawable.lin_journal),
    CliHelpSection("stats", R.string.cli_help_stats_title, R.string.cli_help_stats_body, R.drawable.lin_stats),
    CliHelpSection(
        "firewall",
        R.string.cli_help_firewall_title,
        R.string.cli_help_firewall_body,
        R.drawable.lin_fire,
    ),
    CliHelpSection(
        "security",
        R.string.cli_help_security_title,
        R.string.cli_help_security_body,
        R.drawable.lin_lock,
    ),
    CliHelpSection(
        "sentinel",
        R.string.cli_help_sentinel_title,
        R.string.cli_help_sentinel_body,
        R.drawable.lin_incognito,
    ),
    CliHelpSection(
        "webapps",
        R.string.cli_help_webapps_title,
        R.string.cli_help_webapps_body,
        R.drawable.lin_webapps,
    ),
    CliHelpSection("tile", R.string.cli_help_tile_title, R.string.cli_help_tile_body, R.drawable.lin_power),
    CliHelpSection(
        HELP_KEY_KILLSWITCH,
        R.string.cli_help_killswitch_title,
        R.string.cli_help_killswitch_body,
        R.drawable.lin_forbidden,
    ),
    CliHelpSection(
        "updates",
        R.string.cli_help_updates_title,
        R.string.cli_help_updates_body,
        R.drawable.lin_update,
    ),
    CliHelpSection("backup", R.string.cli_help_backup_title, R.string.cli_help_backup_body, R.drawable.lin_export),
)

private const val HELP_KEY_KILLSWITCH = "killswitch"
private const val HELP_KEY_QUICK_START = "start"
private const val HELP_KEY_SMART = "smart"
private const val HELP_MARQUEE_INITIAL_DELAY_MS = 1_200
private const val HELP_MARQUEE_REPEAT_DELAY_MS = 1_800
private const val SMART_HELP_LINE_COUNT = 4
private const val SMART_HELP_METRIC_COUNT = 3
private const val SMART_HELP_LATENCY_COUNT = 4
private const val SMART_HELP_LABEL_SEPARATOR = '|'
private const val SMART_HELP_METRIC_TABLE_WEIGHT = 0.66f
private const val SMART_HELP_LATENCY_TABLE_WEIGHT = 0.34f
private val SMART_HELP_TABLE_CELL_HEIGHT = 28.dp
