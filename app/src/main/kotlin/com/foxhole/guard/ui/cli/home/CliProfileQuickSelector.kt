package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.Profile
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfilesRouteUiState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliDisclosureGlyph
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliMenuCursor
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.cliMarchingBorder
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.cli.profiles.CliProtocolDropdown
import com.foxhole.guard.ui.cli.profiles.formatExpiryDate
import com.foxhole.guard.ui.cli.profiles.profileCountryCode
import com.foxhole.guard.ui.onQuickSelectorSingleProfileSelected

/**
 * Inline profile picker (`● name · protocol` rows, >=48dp; a smart profile carries the `smart` label
 * plus the canonical disclosure arrow instead of a protocol name). Tapping a SINGLE profile picks it
 * and nothing else: the choice is remembered for the next start whatever the mode is (VPN, VPN+TOR
 * or TOR — a pick has never meant "connect now"), and only changing the profile under a live VPN
 * asks first through the B2 confirm sheet. Either way the selector closes. Tapping a SMART profile
 * expands its available-protocol table in place ([CliProtocolDropdown], NOT the management sheet —
 * that lives on the profiles screen only); choosing a protocol applies it and closes the selector.
 */
@OptIn(ExperimentalFoundationApi::class)
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
        CliProfileSelectorContent(
            viewModel = viewModel,
            profiles = profiles,
            expandedSmartId = expandedSmartId,
            onExpandedSmartChange = { expandedSmartId = it },
            onDone = onDone,
        )
    }
}

@Composable
private fun CliProfileSelectorContent(
    viewModel: HomeViewModel,
    profiles: ProfilesRouteUiState,
    expandedSmartId: Long?,
    onExpandedSmartChange: (Long?) -> Unit,
    onDone: () -> Unit,
) {
    if (!profiles.profilesLoaded) {
        CliProfileSelectorLoadingState()
        return
    }
    if (profiles.profiles.isEmpty()) {
        Text(
            text = stringResource(R.string.cli_home_selector_empty),
            style = CliType.body,
            color = LocalCliColors.current.dim,
            modifier = Modifier.padding(vertical = CliSpacing.md),
        )
        return
    }
    CliProfileSelectorTableHeader()
    CliProfileSelectorTable(
        viewModel = viewModel,
        profiles = profiles,
        expandedSmartId = expandedSmartId,
        onExpandedSmartChange = onExpandedSmartChange,
        onDone = onDone,
    )
}

@Composable
private fun CliProfileSelectorTable(
    viewModel: HomeViewModel,
    profiles: ProfilesRouteUiState,
    expandedSmartId: Long?,
    onExpandedSmartChange: (Long?) -> Unit,
    onDone: () -> Unit,
) {
    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
        itemsIndexed(profiles.profiles, key = { _, profile -> profile.id }) { index, profile ->
            val isSmart = profile.protocolOptions.size > 1
            Column(modifier = Modifier.fillMaxWidth()) {
                if (index > 0) CliRowDivider()
                CliProfileSelectorRow(
                    profile = profile,
                    active = profile.id == profiles.activeProfileId,
                    expanded = expandedSmartId == profile.id,
                    onClick = {
                        if (isSmart) {
                            onExpandedSmartChange(profile.id.takeUnless { expandedSmartId == profile.id })
                        } else {
                            viewModel.onQuickSelectorSingleProfileSelected(profile.id)
                            onDone()
                        }
                    },
                )
                if (isSmart && expandedSmartId == profile.id) {
                    CliProtocolDropdown(
                        viewModel = viewModel,
                        state = profiles,
                        profile = profile,
                        onOptionSelected = {
                            onExpandedSmartChange(null)
                            onDone()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CliProfileSelectorRow(
    profile: Profile,
    active: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalCliColors.current
    val isSmart = profile.protocolOptions.size > 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .cliPressable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CliMenuCursor(selected = active, tint = colors.ok)
        ProfileSelectorFlag(profile)
        Text(
            text = profile.name,
            style = CliType.body,
            color = if (active) colors.fg else colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(PROFILE_SELECTOR_NAME_WEIGHT)
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = PROFILE_SELECTOR_MARQUEE_DELAY_MS,
                    repeatDelayMillis = PROFILE_SELECTOR_MARQUEE_REPEAT_MS,
                ),
        )
        ProfileSelectorProtocol(profile = profile, expanded = expanded, isSmart = isSmart)
        Text(
            text = profile.subscriptionExpiresAt?.let(::formatExpiryDate) ?: "—",
            style = CliType.small,
            color = if (profile.subscriptionExpiresAt == null) colors.faint else colors.warn,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(PROFILE_SELECTOR_EXPIRY_WEIGHT),
        )
    }
}

@Composable
private fun ProfileSelectorFlag(profile: Profile) {
    Box(
        modifier = Modifier.width(PROFILE_SELECTOR_FLAG_SLOT_WIDTH),
        contentAlignment = Alignment.Center,
    ) {
        profileCountryCode(
            profile.protocolOptions.firstOrNull { option -> option.isSelected }?.displayName,
            profile.name,
        )?.let { country -> CliFlagIcon(countryCode = country) }
    }
}

@Composable
private fun RowScope.ProfileSelectorProtocol(
    profile: Profile,
    expanded: Boolean,
    isSmart: Boolean,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.weight(PROFILE_SELECTOR_PROTOCOL_WEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = quickSelectorProtocolLabel(profile),
            style = CliType.small,
            color = colors.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isSmart) {
            Box(
                modifier = Modifier.width(PROFILE_SELECTOR_DISCLOSURE_WIDTH),
                contentAlignment = Alignment.CenterEnd,
            ) {
                CliDisclosureGlyph(expanded = expanded, color = colors.accent)
            }
        } else {
            Spacer(modifier = Modifier.width(PROFILE_SELECTOR_DISCLOSURE_WIDTH))
        }
    }
}

@Composable
private fun CliProfileSelectorTableHeader() {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(PROFILE_SELECTOR_LEADING_WIDTH))
        Text(
            text = stringResource(R.string.cli_home_key_profile),
            style = CliType.small,
            color = colors.faint,
            modifier = Modifier.weight(PROFILE_SELECTOR_NAME_WEIGHT),
        )
        Text(
            text = stringResource(R.string.cli_home_key_protocol),
            style = CliType.small,
            color = colors.faint,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(PROFILE_SELECTOR_PROTOCOL_WEIGHT),
        )
        Text(
            text = stringResource(R.string.cli_prof_facts_expiry),
            style = CliType.small,
            color = colors.faint,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(PROFILE_SELECTOR_EXPIRY_WEIGHT),
        )
    }
}

private fun quickSelectorProtocolLabel(profile: com.foxhole.core.model.Profile): String {
    val selected = profile.protocolOptions.firstOrNull { it.id == profile.selectedProtocolOptionId }
        ?: profile.protocolOptions.firstOrNull { it.isSelected }
    return (selected?.protocolHint ?: profile.protocolHint).name.lowercase()
}

internal const val CLI_PROFILE_SELECTOR_LOADING_TAG = "cli_profile_selector_loading"
private const val PROFILE_SELECTOR_NAME_WEIGHT = 0.43f
private const val PROFILE_SELECTOR_PROTOCOL_WEIGHT = 0.25f
private const val PROFILE_SELECTOR_EXPIRY_WEIGHT = 0.32f
private const val PROFILE_SELECTOR_MARQUEE_DELAY_MS = 1_200
private const val PROFILE_SELECTOR_MARQUEE_REPEAT_MS = 1_000
private val PROFILE_SELECTOR_FLAG_SLOT_WIDTH = 24.dp
private val PROFILE_SELECTOR_LEADING_WIDTH = 40.dp
private val PROFILE_SELECTOR_DISCLOSURE_WIDTH = 18.dp

/** The current inline selector's loading state, kept separate so its UI contract is testable. */
@Composable
internal fun CliProfileSelectorLoadingState(modifier: Modifier = Modifier) {
    val colors = LocalCliColors.current
    Text(
        text = stringResource(R.string.cli_common_loading_data),
        style = CliType.body,
        color = colors.dim,
        modifier = modifier
            .testTag(CLI_PROFILE_SELECTOR_LOADING_TAG)
            .padding(vertical = CliSpacing.md),
    )
}
