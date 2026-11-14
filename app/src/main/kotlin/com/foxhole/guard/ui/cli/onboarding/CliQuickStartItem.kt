package com.foxhole.guard.ui.cli.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.help.GuardHelpBlocks
import com.foxhole.guard.ui.cli.help.guardHelpTopic

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun CliQuickStartItems(body: String, framed: Boolean, modifier: Modifier = Modifier,
    iconColor: Color = Color.Unspecified, smartBody: String? = null, detailsBody: String? = null) {
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(CliSpacing.md)) {
            GuardHelpBlocks(guardHelpTopic("quick_actions").blocks, scope = "quick-actions")
            if (smartBody != null) GuardHelpBlocks(guardHelpTopic("smart").blocks, scope = "quick-smart")
            if (detailsBody != null) GuardHelpBlocks(guardHelpTopic("modes").blocks, scope = "quick-modes")
        }
    }
    if (framed) CliPanel(modifier = modifier.fillMaxWidth()) { content() }
    else Column(modifier) { content() }
}
