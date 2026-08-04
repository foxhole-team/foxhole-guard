package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliDisclosureGlyph
import com.foxhole.guard.ui.cli.components.CliMenuCursor
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.cliMarchingBorder
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.cli.profiles.CliProtocolDropdown
import com.foxhole.guard.ui.onQuickSelectorSingleProfileSelected

/**
 * Inline profile picker (`● name · protocol` rows, >=48dp; a smart profile carries the `smart` label
 * plus the canonical disclosure arrow instead of a protocol name). Tapping a SINGLE profile picks it:
 * while connected the tunnel reconnects onto it via the B2 confirm, while idle it activates and
 * connects straight away; either way the selector closes. Tapping a SMART profile expands its
 * available-protocol table in place ([CliProtocolDropdown], NOT the management sheet — that lives on
 * the profiles screen only); choosing a protocol applies it and closes the selector.
 */
@Composable
internal fun CliProfileQuickSelector(
    viewModel: HomeViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val profiles by viewModel.profilesRouteState.collectAsStateWithLifecycle()
    var expandedSmartId by remember { mutableStateOf<Long?>(null) }
    CliPanel(
        icon = R.drawable.pix_profiles,
        modifier = modifier.fillMaxWidth().cliMarchingBorder(colors.accent),
        title = stringResource(R.string.cli_prof_title),
        // A tap outside the rows pages the area back to the facts; profile rows intercept their
        // own taps first.
        onClick = onDone,
    ) {
        if (!profiles.profilesLoaded) {
            CliProfileSelectorLoadingState()
            return@CliPanel
        }
        if (profiles.profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.cli_home_selector_empty),
                style = CliType.body,
                color = colors.dim,
                modifier = Modifier.padding(vertical = CliSpacing.md),
            )
            return@CliPanel
        }
        // ~3 rows visible then scroll (heightIn max ~300dp).
        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
            items(profiles.profiles, key = { it.id }) { profile ->
                val active = profile.id == profiles.activeProfileId
                val isSmart = profile.protocolOptions.size > 1
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .cliPressable {
                                if (isSmart) {
                                    expandedSmartId = profile.id.takeUnless { expandedSmartId == profile.id }
                                } else {
                                    viewModel.onQuickSelectorSingleProfileSelected(profile.id)
                                    onDone()
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CliMenuCursor(selected = active, tint = colors.ok)
                        Text(
                            text = profile.name,
                            style = CliType.body,
                            color = if (active) colors.fg else colors.dim,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSmart) {
                            // A smart profile is marked and discloses with the canonical arrow
                            // rather than a protocol count; the whole row is clickable, so the
                            // glyph needs no button of its own.
                            Text(
                                text = stringResource(R.string.cli_prof_smart_label),
                                style = CliType.small,
                                color = colors.faint,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            CliDisclosureGlyph(
                                expanded = expandedSmartId == profile.id,
                                color = colors.accent,
                            )
                        } else {
                            Text(
                                text = profile.protocolHint.name.lowercase(),
                                style = CliType.small,
                                color = colors.faint,
                                maxLines = 1,
                            )
                        }
                    }
                    // C: a smart profile expands its available-protocol picker inline; choosing a
                    // protocol applies it (a live switch confirms via B3/P2 on the home screen) and
                    // closes the selector.
                    if (isSmart && expandedSmartId == profile.id) {
                        CliProtocolDropdown(
                            viewModel = viewModel,
                            state = profiles,
                            profile = profile,
                            onOptionSelected = {
                                expandedSmartId = null
                                onDone()
                            },
                        )
                    }
                }
            }
        }
    }
}

internal const val CLI_PROFILE_SELECTOR_LOADING_TAG = "cli_profile_selector_loading"

/** The current inline selector's loading state, kept separate so its UI contract is testable. */
@Composable
internal fun CliProfileSelectorLoadingState(modifier: Modifier = Modifier) {
    val colors = LocalCliColors.current
    Text(
        text = stringResource(R.string.cli_common_loading),
        style = CliType.body,
        color = colors.dim,
        modifier = modifier
            .testTag(CLI_PROFILE_SELECTOR_LOADING_TAG)
            .padding(vertical = CliSpacing.md),
    )
}
