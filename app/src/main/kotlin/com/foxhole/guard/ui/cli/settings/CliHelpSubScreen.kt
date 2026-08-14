package com.foxhole.guard.ui.cli.settings

import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.cliCaptionSpanStyle
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliStringSetSaver
import com.foxhole.guard.ui.cli.components.cliPanelBackground
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.cli.onboarding.CliQuickStartItems

/**
 * Help as a deck of cards: each topic has its own glyph, a title plate, a double border with
 * corner pips and an expanding body. Static content with no view model — the set of expanded cards
 * is the entire state.
 *
 * Everything starts closed. Opening the first card on entry filled the screen with the longest
 * text in the app before the user had chosen a topic, so the deck — the whole point of the screen —
 * was pushed under the fold and help opened on an answer nobody had asked for.
 */
@Composable
internal fun CliHelpSubScreen(
    modifier: Modifier = Modifier,
) {
    var expandedKeys by rememberSaveable(stateSaver = CliStringSetSaver) {
        mutableStateOf(emptySet<String>())
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_help), iconGlyph = "?")
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

/** One card: icon and title plate, text body, border with corner pips. */
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
    val shape = RoundedCornerShape(2.dp)
    val cardBackground = cliPanelBackground(Color.Unspecified, colors.panel, appearance)
    val titleBackground = cliPanelBackground(Color.Unspecified, colors.panelAlt, appearance)
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(cardBackground)
                .border(2.dp, if (expanded) colors.accentDim else colors.border, shape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .background(titleBackground)
                    .cliPressable(onClick = onToggle)
                    .padding(horizontal = CliSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CliPixIcon(
                    id = section.icon,
                    contentDescription = null,
                    size = 16.dp,
                    tint = colors.accent,
                )
                Spacer(modifier = Modifier.width(CliSpacing.sm))
                Text(
                    text = buildAnnotatedString {
                        withStyle(cliCaptionSpanStyle(stringResource(section.titleRes))) {
                            append(stringResource(section.titleRes))
                        }
                    },
                    style = CliType.small,
                    color = if (expanded) colors.fg else colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .weight(1f)
                        // basicMarquee is inert when the title fits; only an actually clipped
                        // localized heading moves, after a readable pause.
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = HELP_MARQUEE_INITIAL_DELAY_MS,
                            repeatDelayMillis = HELP_MARQUEE_REPEAT_DELAY_MS,
                        ),
                )
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = CliType.body,
                    color = colors.accent,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(modifier = Modifier.padding(CliSpacing.md)) {
                    CliRowDivider(modifier = Modifier.padding(bottom = CliSpacing.xs))
                    val body = stringResource(section.bodyRes)
                    if (section.key == HELP_KEY_QUICK_START) {
                        CliQuickStartItems(body = body, framed = false)
                    } else {
                        CliHelpBody(body = body, icon = section.icon)
                    }
                    // Kill switch is an Android feature (VPN lockdown), so the card links straight
                    // to system VPN settings rather than offering an in-app setting.
                    if (section.key == HELP_KEY_KILLSWITCH) {
                        CliActionRow(
                            label = stringResource(R.string.cli_help_killswitch_link),
                            icon = R.drawable.pix_settings,
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
        // Corner pips over the border: the deck's card corners.
        CliHelpCardPips(expanded = expanded)
    }
}

/**
 * Help body text with terminal-style structure: blank lines split paragraphs, and a line
 * opening with a short `term — definition` gets its term set in the accent color, so
 * glossary blocks (modes, journals) read as aligned definition lists rather than prose.
 * The relaxed line height is local to help: these are the longest reading passages in the app.
 */
@Composable
private fun CliHelpBody(
    body: String,
    @DrawableRes icon: Int,
) {
    val colors = LocalCliColors.current
    Column(verticalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
        body.split("\n\n").forEach { paragraph ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                CliPixIcon(
                    id = icon,
                    contentDescription = null,
                    size = 16.dp,
                    tint = colors.accent,
                )
                Spacer(modifier = Modifier.width(CliSpacing.sm))
                Text(
                    text = buildAnnotatedString {
                        paragraph.split("\n").forEachIndexed { index, line ->
                            if (index > 0) append("\n")
                            val term = helpGlossaryTerm(line)
                            if (term != null) {
                                withStyle(SpanStyle(color = colors.accent)) { append(term) }
                                append(line.substring(term.length))
                            } else {
                                append(line)
                            }
                        }
                    },
                    style = CliType.body.copy(lineHeight = 22.sp),
                    color = colors.fg,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The leading term of a `term — definition` line, or null. Bounded length plus the
 * no-punctuation guard keep mid-sentence em dashes («нажми START — терминал…») unstyled.
 */
private fun helpGlossaryTerm(line: String): String? {
    val dash = line.indexOf(" — ")
    if (dash <= 0 || dash > HELP_TERM_MAX_CHARS) return null
    val term = line.take(dash)
    if (term.any { it in HELP_TERM_STOP_CHARS }) return null
    return term
}

private const val HELP_TERM_MAX_CHARS = 28

// Punctuation that proves the dash was mid-sentence rather than a glossary separator.
private const val HELP_TERM_STOP_CHARS = ".,:;"

@Composable
private fun BoxScope.CliHelpCardPips(expanded: Boolean) {
    val colors = LocalCliColors.current
    val tint = if (expanded) colors.accent else colors.borderBright
    listOf(
        Alignment.TopStart,
        Alignment.TopEnd,
        Alignment.BottomStart,
        Alignment.BottomEnd,
    ).forEach { corner ->
        Box(
            modifier = Modifier
                .align(corner)
                .size(4.dp)
                .background(tint),
        )
    }
}

private class CliHelpSection(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    @DrawableRes val icon: Int,
)

// Full feature coverage apart from I2P, whose surface is frozen. Ordered from first run towards
// subtler matters, with related topics adjacent.
private val HELP_SECTIONS = listOf(
    CliHelpSection(
        HELP_KEY_QUICK_START,
        R.string.cli_help_start_title,
        R.string.cli_help_start_body,
        R.drawable.pix_power,
    ),
    CliHelpSection("modes", R.string.cli_help_modes_title, R.string.cli_help_modes_body, R.drawable.pix_shield),
    CliHelpSection("tor", R.string.cli_help_tor_title, R.string.cli_help_tor_body, R.drawable.pix_tor),
    CliHelpSection("smart", R.string.cli_help_smart_title, R.string.cli_help_smart_body, R.drawable.pix_star),
    CliHelpSection("editor", R.string.cli_help_editor_title, R.string.cli_help_editor_body, R.drawable.pix_edit),
    CliHelpSection(
        "transfer",
        R.string.cli_help_transfer_title,
        R.string.cli_help_transfer_body,
        R.drawable.pix_import,
    ),
    CliHelpSection("routing", R.string.cli_help_routing_title, R.string.cli_help_routing_body, R.drawable.pix_link),
    CliHelpSection("dns", R.string.cli_help_dns_title, R.string.cli_help_dns_body, R.drawable.pix_dns),
    CliHelpSection(
        "net_rules",
        R.string.cli_help_net_rules_title,
        R.string.cli_help_net_rules_body,
        R.drawable.pix_globe,
    ),
    CliHelpSection(
        "lan_proxy",
        R.string.cli_help_lan_proxy_title,
        R.string.cli_help_lan_proxy_body,
        R.drawable.pix_device,
    ),
    CliHelpSection("logs", R.string.cli_help_logs_title, R.string.cli_help_logs_body, R.drawable.pix_journal),
    CliHelpSection("stats", R.string.cli_help_stats_title, R.string.cli_help_stats_body, R.drawable.pix_stats),
    CliHelpSection(
        "firewall",
        R.string.cli_help_firewall_title,
        R.string.cli_help_firewall_body,
        R.drawable.pix_fire,
    ),
    CliHelpSection(
        "security",
        R.string.cli_help_security_title,
        R.string.cli_help_security_body,
        R.drawable.pix_lock,
    ),
    CliHelpSection(
        "sentinel",
        R.string.cli_help_sentinel_title,
        R.string.cli_help_sentinel_body,
        R.drawable.pix_incognito,
    ),
    CliHelpSection(
        "webapps",
        R.string.cli_help_webapps_title,
        R.string.cli_help_webapps_body,
        R.drawable.pix_webapps,
    ),
    CliHelpSection("tile", R.string.cli_help_tile_title, R.string.cli_help_tile_body, R.drawable.pix_power),
    CliHelpSection(
        HELP_KEY_KILLSWITCH,
        R.string.cli_help_killswitch_title,
        R.string.cli_help_killswitch_body,
        R.drawable.pix_forbidden,
    ),
    CliHelpSection(
        "updates",
        R.string.cli_help_updates_title,
        R.string.cli_help_updates_body,
        R.drawable.pix_update,
    ),
    CliHelpSection("backup", R.string.cli_help_backup_title, R.string.cli_help_backup_body, R.drawable.pix_export),
)

private const val HELP_KEY_KILLSWITCH = "killswitch"
private const val HELP_KEY_QUICK_START = "start"
private const val HELP_MARQUEE_INITIAL_DELAY_MS = 1_200
private const val HELP_MARQUEE_REPEAT_DELAY_MS = 1_800
