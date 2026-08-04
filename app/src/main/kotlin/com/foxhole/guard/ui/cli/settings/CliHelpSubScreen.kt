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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliCaptionSpanStyle
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliStringSetSaver
import com.foxhole.guard.ui.cli.components.cliPressable

/**
 * Help as a deck of cards: each topic has its own glyph, a title plate, a double border with
 * corner pips and an expanding body. Static content with no view model — the set of expanded cards
 * is the entire state, and the first card opens on entry.
 */
@Composable
internal fun CliHelpSubScreen(
    modifier: Modifier = Modifier,
) {
    var expandedKeys by rememberSaveable(stateSaver = CliStringSetSaver) {
        mutableStateOf(setOf(HELP_SECTIONS.first().key))
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_help), icon = R.drawable.pix_info)
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
@Composable
private fun CliHelpCard(
    section: CliHelpSection,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalCliColors.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(2.dp)
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panel)
                .border(2.dp, if (expanded) colors.accentDim else colors.border, shape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .background(colors.panelAlt)
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
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
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
                    Text(
                        text = stringResource(section.bodyRes),
                        style = CliType.body,
                        color = colors.fg,
                    )
                    // Kill switch is an Android feature (VPN lockdown), so the card links straight
                    // to system VPN settings rather than offering an in-app setting.
                    if (section.key == HELP_KEY_KILLSWITCH) {
                        CliActionRow(
                            label = stringResource(R.string.cli_help_killswitch_link),
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
    CliHelpSection("start", R.string.cli_help_start_title, R.string.cli_help_start_body, R.drawable.pix_power),
    CliHelpSection(
        "terminal",
        R.string.cli_help_terminal_title,
        R.string.cli_help_terminal_body,
        R.drawable.pix_status,
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
    CliHelpSection("map", R.string.cli_help_map_title, R.string.cli_help_map_body, R.drawable.pix_map),
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
    CliHelpSection("widgets", R.string.cli_help_widgets_title, R.string.cli_help_widgets_body, R.drawable.pix_home),
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
    CliHelpSection(
        "file_share",
        R.string.cli_help_file_share_title,
        R.string.cli_help_file_share_body,
        R.drawable.pix_copy,
    ),
)

private const val HELP_KEY_KILLSWITCH = "killswitch"
