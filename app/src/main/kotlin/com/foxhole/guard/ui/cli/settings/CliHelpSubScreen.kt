package com.foxhole.guard.ui.cli.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.*
import com.foxhole.guard.ui.cli.components.*
import com.foxhole.guard.ui.cli.help.*

@Composable
internal fun CliHelpSubScreen(modifier: Modifier = Modifier, state: GuardHelpState = rememberGuardHelpState()) {
    val colors = LocalCliColors.current
    var expandedKeys by state.expanded
    val scroll = state.scroll
    Column(modifier.fillMaxSize().padding(horizontal = CliSpacing.md)) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_help), iconGlyph = "?", iconColor = colors.info)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = LocalCliBottomChromeClearance.current),
            verticalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            guardHelpTopics().forEach { section ->
                key(section.id) {
                    CliHelpCard(section, section.id in expandedKeys) {
                        expandedKeys = if (section.id in expandedKeys) expandedKeys - section.id else expandedKeys + section.id
                    }
                }
            }
        }
    }
}

@Composable
private fun CliHelpCard(section: GuardHelpTopic, expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalCliColors.current
    val state = stringResource(if (expanded) R.string.help_expanded else R.string.help_collapsed)
    CliPanel(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .cliPressable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { heading(); stateDescription = state },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliHelpTopicIcon(section.icon)
            Text(cliHeadingText(stringResource(section.title)), Modifier.weight(1f), style = cliCaptionTextStyle(),
                color = if (expanded) colors.fg else colors.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (expanded) "▾" else "▸", Modifier.clearAndSetSemantics {}, style = CliType.body, color = colors.info)
        }
        AnimatedVisibility(expanded, enter = cliVerticalEnter(), exit = cliVerticalExit()) {
            GuardHelpBlocks(section.blocks, Modifier.padding(top = CliSpacing.sm), scope = section.id)
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun CliSmartHelpBody(body: String, @DrawableRes icon: Int, framed: Boolean = false) {
    val content: @Composable () -> Unit = { GuardHelpBlocks(guardHelpTopic("smart").blocks, scope = "smart") }
    if (framed) CliPanel(modifier = Modifier.fillMaxWidth()) { content() } else content()
}

@Composable
private fun CliHelpTopicIcon(@DrawableRes icon: Int) {
    CliIcon(id = icon, contentDescription = null, size = 20.dp, tint = LocalCliColors.current.info)
}
