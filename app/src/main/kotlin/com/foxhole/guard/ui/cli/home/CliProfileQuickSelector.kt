package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfilesRouteUiState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.components.CLI_ACTIVE_DOT_SLOT_WIDTH
import com.foxhole.guard.ui.cli.components.CLI_MENU_ROW_MIN_HEIGHT
import com.foxhole.guard.ui.cli.components.CLI_PROFILE_TABLE_RIM
import com.foxhole.guard.ui.cli.components.CliActiveDot
import com.foxhole.guard.ui.cli.components.CliColumnRule
import com.foxhole.guard.ui.cli.components.CliDisclosureGlyph
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.cliPanelBackground
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.cli.profiles.CliProtocolCompactHostWidth
import com.foxhole.guard.ui.cli.profiles.CliProtocolOptionRow
import com.foxhole.guard.ui.cli.profiles.CliProtocolTableHeader
import com.foxhole.guard.ui.cli.profiles.formatExpiryDate
import com.foxhole.guard.ui.cli.profiles.profileCountryCode
import com.foxhole.guard.ui.cli.profiles.protocolRowPresentation
import com.foxhole.guard.ui.cli.profiles.rememberCliProtocolColumnWidths

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CliProfileQuickSelector(
    viewModel: HomeViewModel,
    expandedSmartId: Long?,
    onExpandedSmartChange: (Long?) -> Unit,
    onProfileSelected: (Long) -> Unit,
    onProtocolSelected: (Long, String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val profiles by viewModel.profilesRouteState.collectAsStateWithLifecycle()
    CliPanel(
        icon = R.drawable.lin_profiles,
        modifier = modifier.fillMaxWidth(),
        title = stringResource(R.string.cli_prof_title),
        accentBorderColor = colors.accent,
        onClick = onDone,
    ) {
        CliProfileSelectorContent(
            profiles = profiles,
            expandedSmartId = expandedSmartId,
            onExpandedSmartChange = onExpandedSmartChange,
            onProfileSelected = onProfileSelected,
            onProtocolSelected = onProtocolSelected,
        )
    }
}

@Composable
private fun CliProfileSelectorContent(
    profiles: ProfilesRouteUiState,
    expandedSmartId: Long?,
    onExpandedSmartChange: (Long?) -> Unit,
    onProfileSelected: (Long) -> Unit,
    onProtocolSelected: (Long, String) -> Unit,
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
    CliRowDivider()
    CliProfileSelectorTable(
        profiles = profiles,
        expandedSmartId = expandedSmartId,
        onExpandedSmartChange = onExpandedSmartChange,
        onProfileSelected = onProfileSelected,
        onProtocolSelected = onProtocolSelected,
    )
}

@Composable
private fun CliProfileSelectorTable(
    profiles: ProfilesRouteUiState,
    expandedSmartId: Long?,
    onExpandedSmartChange: (Long?) -> Unit,
    onProfileSelected: (Long) -> Unit,
    onProtocolSelected: (Long, String) -> Unit,
) {
    val colors = LocalCliColors.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cellSpacing = if (maxWidth < CliProtocolCompactHostWidth) 2.dp else CliSpacing.xs
        val expandedProfile = profiles.profiles.firstOrNull { profile ->
            profile.id == expandedSmartId && profile.protocolOptions.size > 1
        }
        val expandedWidths = expandedProfile?.let { profile ->
            rememberCliProtocolColumnWidths(
                state = profiles,
                profile = profile,
                hostWidth = maxWidth,
                cellSpacing = cellSpacing,
            )
        }
        val expandedSurface = cliPanelBackground(
            candidate = Color.Unspecified,
            fallback = colors.panelAlt,
            appearance = LocalCliPanelAppearance.current,
        )
        LazyColumn(
            modifier = Modifier.heightIn(max = 300.dp),
        ) {
            profiles.profiles.forEachIndexed { index, profile ->
                val isSmart = profile.protocolOptions.size > 1
                item(key = profile.id) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (index > 0) CliRowDivider()
                        CliProfileSelectorRow(
                            profile = profile,
                            active = profile.id == profiles.activeProfileId,
                            expanded = expandedSmartId == profile.id,
                            onClick = {
                                if (isSmart) {
                                    onExpandedSmartChange(
                                        profile.id.takeUnless { expandedSmartId == profile.id },
                                    )
                                } else {
                                    onProfileSelected(profile.id)
                                }
                            },
                        )
                    }
                }
                if (profile.id == expandedProfile?.id && expandedWidths != null) {
                    stickyHeader(key = "proto-header-${profile.id}") {
                        Column(modifier = Modifier.fillMaxWidth().background(expandedSurface)) {
                            CliProtocolTableHeader(
                                widths = expandedWidths,
                                cellSpacing = cellSpacing,
                                showStatus = false,
                                nameLabel = "v2raytun",
                            )
                            CliRowDivider()
                        }
                    }
                    val options = profile.protocolOptions.filter(ProfileProtocolOption::enabled)
                    itemsIndexed(
                        options,
                        key = { _, option -> "proto-${profile.id}-${option.id}" },
                    ) { optionIndex, option ->
                        Column(modifier = Modifier.fillMaxWidth().background(expandedSurface)) {
                            if (optionIndex > 0) CliRowDivider()
                            CliProtocolOptionRow(
                                presentation = protocolRowPresentation(profiles, profile, option),
                                widths = expandedWidths,
                                cellSpacing = cellSpacing,
                                showStatus = false,
                                onClick = {
                                    if (option.enabled) {
                                        onProtocolSelected(profile.id, option.id)
                                    }
                                },
                                onToggleEnabled = null,
                            )
                        }
                    }
                    stickyHeader(key = "proto-boundary-${profile.id}") {
                        Spacer(modifier = Modifier.fillMaxWidth().height(PROTOCOL_STICKY_BOUNDARY_HEIGHT))
                    }
                }
            }
        }
    }
}

private val PROTOCOL_STICKY_BOUNDARY_HEIGHT = 1.dp

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
            .defaultMinSize(minHeight = CLI_MENU_ROW_MIN_HEIGHT)
            .cliPressable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileSelectorLeadingSlot(profile = profile, active = active)
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
        CliColumnRule()
        Text(
            text = profile.subscriptionExpiresAt?.let(::formatExpiryDate) ?: "—",
            style = CliType.small,
            color = if (profile.subscriptionExpiresAt == null) colors.faint else colors.warn,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(PROFILE_SELECTOR_EXPIRY_WEIGHT),
        )
        CliColumnRule()
        ProfileSelectorProtocol(profile = profile, expanded = expanded, isSmart = isSmart)
    }
}

@Composable
private fun ProfileSelectorLeadingSlot(profile: Profile, active: Boolean) {
    Row(
        modifier = Modifier.width(PROFILE_SELECTOR_LEADING_SLOT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CliActiveDot(active = active)
        profileCountryCode(
            profile.protocolOptions.firstOrNull { option -> option.isSelected }?.displayName,
            profile.name,
        )?.let { country -> CliFlagIcon(countryCode = country, style = CliType.small) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ProfileSelectorProtocol(
    profile: Profile,
    expanded: Boolean,
    isSmart: Boolean,
) {
    val colors = LocalCliColors.current
    Text(
        text = quickSelectorProtocolLabel(profile),
        style = CliType.small,
        color = colors.faint,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.End,
        modifier = Modifier
            .weight(PROFILE_SELECTOR_PROTOCOL_WEIGHT)
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = PROFILE_SELECTOR_MARQUEE_DELAY_MS,
                repeatDelayMillis = PROFILE_SELECTOR_MARQUEE_REPEAT_MS,
            ),
    )
    Box(
        modifier = Modifier.width(CLI_PROFILE_TABLE_RIM),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (isSmart) {
            CliDisclosureGlyph(expanded = expanded, color = colors.accent)
        }
    }
}

@Composable
private fun CliProfileSelectorTableHeader() {
    val colors = LocalCliColors.current
    val captionStyle = profileSelectorCaptionStyle()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(PROFILE_SELECTOR_LEADING_SLOT))
        Text(
            text = cliLabelText(stringResource(R.string.cli_home_key_profile)),
            style = captionStyle,
            color = colors.faint,
            modifier = Modifier.weight(PROFILE_SELECTOR_NAME_WEIGHT),
        )
        CliColumnRule()
        Text(
            text = cliLabelText(stringResource(R.string.cli_prof_facts_expiry)),
            style = captionStyle,
            color = colors.faint,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(PROFILE_SELECTOR_EXPIRY_WEIGHT),
        )
        CliColumnRule()
        Text(
            text = cliLabelText(stringResource(R.string.cli_home_key_protocol)),
            style = captionStyle,
            color = colors.faint,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(PROFILE_SELECTOR_PROTOCOL_WEIGHT),
        )
        Spacer(modifier = Modifier.width(CLI_PROFILE_TABLE_RIM))
    }
}

@Composable
@ReadOnlyComposable
private fun profileSelectorCaptionStyle(): TextStyle = CliType.small

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

private val PROFILE_SELECTOR_LEADING_SLOT = CLI_PROFILE_TABLE_RIM + CLI_ACTIVE_DOT_SLOT_WIDTH

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
