package com.foxhole.guard.ui.cli.profiles

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.Profile
import com.foxhole.core.model.VisualStyle
import com.foxhole.core.profile.exportableProfileChoices
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfilesExportSelectionState
import com.foxhole.guard.ui.ProfilesRouteUiState
import com.foxhole.guard.ui.SmartProfileExportSelectionState
import com.foxhole.guard.ui.cli.CliMotion
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.cliMetricSp
import com.foxhole.guard.ui.cli.components.CLI_PROFILE_TABLE_RIM
import com.foxhole.guard.ui.cli.components.CliActiveDot
import com.foxhole.guard.ui.cli.components.CliColumnRule
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliDisclosureGlyph
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.cliAccentSweepBorder
import com.foxhole.guard.ui.cli.components.cliCombinedPressable
import com.foxhole.guard.ui.cli.components.cliMarchingBorder
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.deleteProfile
import com.foxhole.guard.ui.isReady
import com.foxhole.guard.ui.isSmartProfileExpanded
import com.foxhole.guard.ui.onActivateProfileRequested
import com.foxhole.guard.ui.renameProfile
import com.foxhole.guard.ui.selectedKeys
import com.foxhole.guard.ui.smartProfileSelectionState
import com.foxhole.guard.ui.toggleSingleProfile
import com.foxhole.guard.ui.toggleSmartProfileAll
import com.foxhole.guard.ui.toggleSmartProfileChoice
import com.foxhole.guard.ui.toggleSmartProfileExpanded

@Composable
internal fun CliProfileListItem(
    viewModel: HomeViewModel,
    state: ProfilesRouteUiState,
    profile: Profile,
    selection: ProfilesExportSelectionState,
    pendingDeleteId: Long?,
    expandedSmartId: Long?,
    actions: CliProfilesActions,
) {
    val selectionMode = selection.isReady()
    val rowSelection = selection.smartProfileSelectionState(profile).takeIf { selectionMode }
    val isSmart = profile.protocolOptions.size > 1
    val exportChoices = remember(profile) { exportableProfileChoices(profile) }
    val exportExpanded = selectionMode && selection.isSmartProfileExpanded(profile.id)
    val toggleSelection = {
        actions.changePendingDelete(null)
        actions.changeSelection(selection.toggledProfile(profile))
    }
    var editorOpen by rememberSaveable(profile.id) { mutableStateOf(false) }
    var detailOpen by rememberSaveable(profile.id) { mutableStateOf(false) }
    Column {
        CliProfileRow(
            profile = profile,
            active = profile.id == state.activeProfileId,
            deleteArmed = pendingDeleteId == profile.id,
            smartExpanded = exportExpanded || expandedSmartId == profile.id,
            selectionState = rowSelection,
            onTap = {
                handleProfileRowTap(
                    selectionMode = selectionMode,
                    isSmart = isSmart,
                    pendingDeleteId = pendingDeleteId,
                    profile = profile,
                    selection = selection,
                    actions = actions,
                    toggleSelection = toggleSelection,
                    openDetail = { detailOpen = true },
                )
            },
            onLongPress = {
                handleProfileRowLongPress(
                    selectionMode = selectionMode,
                    profileId = profile.id,
                    actions = actions,
                    toggleSelection = toggleSelection,
                )
            },
            onDeleteConfirm = {
                actions.changePendingDelete(null)
                viewModel.deleteProfile(profile.id)
            },
            onRenameSubmit = { name ->
                actions.changePendingDelete(null)
                viewModel.renameProfile(profile.id, name)
            },
            onOpenEditor = {
                actions.changePendingDelete(null)
                editorOpen = true
            },
            onSelectForExport = {
                actions.changePendingDelete(null)
                actions.changeSelection(selection.selectedForExport(profile, expandSmart = true))
            },
        )
        if (exportExpanded && exportChoices.size > 1) {
            CliSmartExportChoiceRows(
                choices = exportChoices,
                selectedKeys = selection.selectedKeys(profile.id),
                onToggle = { selectionKey ->
                    actions.changeSelection(selection.toggleSmartProfileChoice(profile, selectionKey))
                },
            )
        }
        if (detailOpen) {
            CliProfileDetailSheet(
                viewModel = viewModel,
                state = state,
                profile = profile,
                isActive = profile.id == state.activeProfileId,
                onActivate = {
                    detailOpen = false
                    viewModel.onActivateProfileRequested(profile.id)
                },
                onDismiss = { detailOpen = false },
            )
        }
        if (editorOpen) {
            CliProfileEditorScreen(
                viewModel = viewModel,
                profile = profile,
                onDismiss = { editorOpen = false },
            )
        }
        if (expandedSmartId == profile.id && isSmart) {
            CliSmartProfileSheet(
                viewModel = viewModel,
                state = state,
                profile = profile,
                onDismiss = { actions.changeExpandedSmart(null) },
            )
        }
    }
}

private fun handleProfileRowTap(
    selectionMode: Boolean,
    isSmart: Boolean,
    pendingDeleteId: Long?,
    profile: Profile,
    selection: ProfilesExportSelectionState,
    actions: CliProfilesActions,
    toggleSelection: () -> Unit,
    openDetail: () -> Unit,
) {
    when {
        selectionMode && isSmart -> {
            actions.changePendingDelete(null)
            actions.changeSelection(selection.toggleSmartProfileExpanded(profile.id))
        }
        selectionMode -> toggleSelection()
        pendingDeleteId != null -> actions.changePendingDelete(null)
        isSmart -> actions.changeExpandedSmart(profile.id)
        else -> openDetail()
    }
}

private fun handleProfileRowLongPress(
    selectionMode: Boolean,
    profileId: Long,
    actions: CliProfilesActions,
    toggleSelection: () -> Unit,
) {
    if (selectionMode) {
        toggleSelection()
    } else {
        actions.changePendingDelete(profileId)
    }
}

internal fun ProfilesExportSelectionState.toggledProfile(profile: Profile): ProfilesExportSelectionState =
    if (exportableProfileChoices(profile).size > 1) {
        toggleSmartProfileAll(profile)
    } else {
        toggleSingleProfile(profile)
    }

private fun ProfilesExportSelectionState.selectedForExport(
    profile: Profile,
    expandSmart: Boolean,
): ProfilesExportSelectionState {
    val selected = toggledProfile(profile)
    return if (
        expandSmart &&
        exportableProfileChoices(profile).size > 1 &&
        !selected.isSmartProfileExpanded(profile.id)
    ) {
        selected.toggleSmartProfileExpanded(profile.id)
    } else {
        selected
    }
}

@Composable
private fun CliSmartExportChoiceRows(
    choices: List<com.foxhole.core.profile.ProfileExportChoice>,
    selectedKeys: Set<String>,
    onToggle: (String) -> Unit,
) {
    val colors = LocalCliColors.current
    val actionEdge = if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        Modifier.cliAccentSweepBorder(colors.accent)
    } else {
        Modifier.cliMarchingBorder(colors.accent)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CLI_PROFILE_TABLE_RIM, end = CliSpacing.xs, bottom = CliSpacing.xs),
    ) {
        choices.forEach { choice ->
            val selected = choice.selectionKey in selectedKeys
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 38.dp)
                    .then(actionEdge)
                    .cliPressable(onClick = { onToggle(choice.selectionKey) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selected) "[x]" else "[ ]",
                    style = CliType.small,
                    color = if (selected) colors.ok else colors.dim,
                )
                Spacer(modifier = Modifier.width(CliSpacing.sm))
                Text(
                    text = choice.displayName,
                    style = CliType.small,
                    color = if (selected) colors.fg else colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CliProfileRow(
    profile: Profile,
    active: Boolean,
    deleteArmed: Boolean,
    smartExpanded: Boolean,
    selectionState: SmartProfileExportSelectionState?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onRenameSubmit: (String) -> Unit,
    onOpenEditor: () -> Unit,
    onSelectForExport: () -> Unit,
) {
    val colors = LocalCliColors.current
    val selected =
        selectionState != null && selectionState != SmartProfileExportSelectionState.NONE
    val selectedEdge = if (selected) {
        if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
            Modifier.cliAccentSweepBorder(colors.accent, radius = 4.dp)
        } else {
            Modifier
        }
    } else {
        Modifier
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .selectedProfileDecoration(selected, colors.accent)
                .then(selectedEdge)
                .cliCombinedPressable(onClick = onTap, onLongClick = onLongPress),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileTableLeadingCell(profile = profile, active = active)
            ProfileTableNameCell(
                profile = profile,
                active = active,
                selected = selected,
                selectionState = selectionState,
                modifier = Modifier.weight(PROFILE_TABLE_NAME_WEIGHT),
            )
            CliColumnRule()
            Text(
                text = profile.subscriptionExpiresAt?.let(::formatExpiryDate) ?: "—",
                style = CliType.small,
                color = if (profile.subscriptionExpiresAt == null) colors.faint else colors.warn,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(PROFILE_TABLE_EXPIRY_WEIGHT),
            )
            CliColumnRule()
            Text(
                text = profileTableProtocolLabel(profile),
                style = CliType.small,
                color = if (profile.requiresInsecureTls) colors.warn else colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(PROFILE_TABLE_PROTOCOL_WEIGHT),
            )
            Box(
                modifier = Modifier.width(CLI_PROFILE_TABLE_RIM),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (profile.protocolOptions.size > 1) {
                    CliDisclosureGlyph(expanded = smartExpanded, color = colors.accent)
                }
            }
        }
        AnimatedVisibility(
            visible = deleteArmed,
            enter = expandVertically(animationSpec = CliMotion.enter()) +
                fadeIn(animationSpec = CliMotion.enter()),
            exit = shrinkVertically(animationSpec = CliMotion.exit()) +
                fadeOut(animationSpec = CliMotion.exit()),
        ) {
            CliProfileActionsRow(
                profileName = profile.name,
                onRenameSubmit = onRenameSubmit,
                onOpenEditor = onOpenEditor,
                onDeleteConfirm = onDeleteConfirm,
                onSelectForExport = onSelectForExport,
            )
        }
    }
}

@Composable
private fun ProfileTableLeadingCell(profile: Profile, active: Boolean) {
    Box(
        modifier = Modifier.width(CLI_PROFILE_TABLE_RIM),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (active) {
            CliActiveDot(active = true)
            return@Box
        }
        profileCountryCode(
            profile.protocolOptions.firstOrNull { it.isSelected }?.displayName,
            profile.name,
        )?.let { country -> CliFlagIcon(countryCode = country, style = CliType.small) }
    }
}

@Composable
private fun ProfileTableNameCell(
    profile: Profile,
    active: Boolean,
    selected: Boolean,
    selectionState: SmartProfileExportSelectionState?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (selectionState != null) {
            Text(
                text = profileSelectionMarker(selectionState, active).trimEnd(),
                style = CliType.body,
                color = profileSelectionColor(selectionState, active, selected, colors),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(PROFILE_TABLE_MARKER_GAP))
        }
        Text(
            text = profile.name,
            style = CliType.body,
            color = if (active) colors.fg else colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f)
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = PROFILE_TABLE_MARQUEE_DELAY_MS,
                    repeatDelayMillis = PROFILE_TABLE_MARQUEE_REPEAT_MS,
                ),
        )
    }
}

@Composable
internal fun CliProfileTableHeader() {
    val colors = LocalCliColors.current
    val style = cliProfileTableHeaderStyle()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(CLI_PROFILE_TABLE_RIM))
        Text(
            text = stringResource(R.string.cli_home_key_profile),
            style = style,
            color = colors.faint,
            modifier = Modifier.weight(PROFILE_TABLE_NAME_WEIGHT),
        )
        CliColumnRule()
        Text(
            text = stringResource(R.string.cli_prof_facts_expiry),
            style = style,
            color = colors.faint,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(PROFILE_TABLE_EXPIRY_WEIGHT),
        )
        CliColumnRule()
        Text(
            text = stringResource(R.string.cli_home_key_protocol),
            style = style,
            color = colors.faint,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(PROFILE_TABLE_PROTOCOL_WEIGHT),
        )
        Spacer(modifier = Modifier.width(CLI_PROFILE_TABLE_RIM))
    }
}

@Composable
@ReadOnlyComposable
internal fun cliProfileTableHeaderStyle(): TextStyle =
    CliType.small.copy(
        fontSize = cliMetricSp(PROFILE_TABLE_HEADER_SP),
        lineHeight = cliMetricSp(PROFILE_TABLE_HEADER_LINE_SP),
    )

private const val PROFILE_TABLE_HEADER_SP = 12f
private const val PROFILE_TABLE_HEADER_LINE_SP = 15f

internal fun profileTableProtocolLabel(profile: Profile): String {
    val selected = profile.protocolOptions.firstOrNull { it.id == profile.selectedProtocolOptionId }
        ?: profile.protocolOptions.firstOrNull { it.isSelected }
    return (selected?.protocolHint ?: profile.protocolHint).name.lowercase()
}

private enum class CliProfileActionStage { MENU, RENAME, DELETE }

private val CliProfileActionStage.showsMenu: Boolean
    get() = this != CliProfileActionStage.RENAME

@Composable
private fun CliProfileActionsRow(
    profileName: String,
    onRenameSubmit: (String) -> Unit,
    onOpenEditor: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onSelectForExport: () -> Unit,
) {
    val colors = LocalCliColors.current
    val actionEdge = if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        Modifier.cliAccentSweepBorder(colors.accent)
    } else {
        Modifier.cliMarchingBorder(colors.accent)
    }
    var stage by remember(profileName) { mutableStateOf(CliProfileActionStage.MENU) }
    var nameText by remember(profileName) { mutableStateOf(profileName) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 6.dp)
            .then(actionEdge)
            .padding(CliSpacing.xs),
    ) {
        AnimatedContent(
            targetState = stage.showsMenu,
            transitionSpec = {
                (
                    fadeIn(animationSpec = CliMotion.enter()) +
                        expandVertically(animationSpec = CliMotion.enter())
                    ) togetherWith
                    (
                        fadeOut(animationSpec = CliMotion.exit()) +
                            shrinkVertically(animationSpec = CliMotion.exit())
                        )
            },
            label = "profileActionStage",
        ) { menuShown ->
            if (menuShown) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val compact = maxWidth / PROFILE_ACTION_COUNT < PROFILE_ACTION_COMPACT_ITEM_WIDTH
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CliProfileActionGlyph(
                            icon = R.drawable.pix_check,
                            contentDescription = stringResource(R.string.cli_prof_act_select),
                            label = stringResource(R.string.cli_prof_act_select),
                            tint = colors.ok,
                            onClick = onSelectForExport,
                            compact = compact,
                            modifier = Modifier.weight(1f),
                        )
                        CliProfileActionGlyph(
                            icon = R.drawable.pix_edit,
                            contentDescription = stringResource(R.string.cli_prof_act_rename),
                            label = stringResource(R.string.cli_prof_act_rename),
                            tint = colors.fg,
                            onClick = { stage = CliProfileActionStage.RENAME },
                            compact = compact,
                            modifier = Modifier.weight(1f),
                        )
                        CliProfileActionGlyph(
                            icon = R.drawable.pix_settings,
                            contentDescription = stringResource(R.string.cli_prof_act_edit),
                            label = stringResource(R.string.cli_prof_act_edit),
                            tint = colors.info,
                            onClick = onOpenEditor,
                            compact = compact,
                            modifier = Modifier.weight(1f),
                        )
                        CliProfileActionGlyph(
                            icon = R.drawable.pix_trash,
                            contentDescription = stringResource(R.string.cli_prof_act_delete),
                            label = stringResource(R.string.cli_prof_act_delete),
                            tint = colors.err,
                            onClick = { stage = CliProfileActionStage.DELETE },
                            compact = compact,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                CliInputRow(
                    prompt = stringResource(R.string.cli_prof_rename_prompt),
                    value = nameText,
                    onValueChange = { nameText = it },
                    onSubmit = { onRenameSubmit(nameText) },
                    autoFocus = true,
                )
            }
        }
    }
    if (stage == CliProfileActionStage.DELETE) {
        CliConfirmSheet(
            title = stringResource(R.string.cli_prof_act_delete),
            icon = R.drawable.pix_trash,
            question = stringResource(R.string.cli_prof_delete_confirm, profileName),
            onConfirm = onDeleteConfirm,
            onDismiss = { stage = CliProfileActionStage.MENU },
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun CliProfileActionGlyph(
    @DrawableRes icon: Int,
    contentDescription: String,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .defaultMinSize(
                minHeight = if (compact) PROFILE_ACTION_GLYPH_HEIGHT_COMPACT else PROFILE_ACTION_GLYPH_HEIGHT,
            )
            .cliPressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CliPixIcon(
                id = icon,
                contentDescription = contentDescription,
                tint = tint,
                size = if (compact) 16.dp else 20.dp,
            )
            Text(
                text = cliLabelText(label),
                style = CliType.small.copy(
                    fontSize = cliMetricSp(if (compact) PROFILE_ACTION_LABEL_COMPACT_SP else PROFILE_ACTION_LABEL_SP),
                    lineHeight = cliMetricSp(
                        if (compact) PROFILE_ACTION_LABEL_COMPACT_LINE_SP else PROFILE_ACTION_LABEL_LINE_SP,
                    ),
                ),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val PROFILE_ACTION_GLYPH_HEIGHT = 56.dp
private val PROFILE_ACTION_GLYPH_HEIGHT_COMPACT = 48.dp
private const val PROFILE_ACTION_LABEL_SP = 11f
private const val PROFILE_ACTION_LABEL_LINE_SP = 13f
private const val PROFILE_ACTION_LABEL_COMPACT_SP = 10f
private const val PROFILE_ACTION_LABEL_COMPACT_LINE_SP = 12f
private const val PROFILE_ACTION_COUNT = 4

private val PROFILE_ACTION_COMPACT_ITEM_WIDTH = 76.dp

private fun Modifier.selectedProfileDecoration(
    selected: Boolean,
    accent: Color,
): Modifier =
    if (selected) {
        background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.6f))
            .padding(horizontal = CliSpacing.xs)
    } else {
        this
    }

private fun profileSelectionMarker(
    selectionState: SmartProfileExportSelectionState?,
    active: Boolean,
): String = when (selectionState) {
    null -> if (active) "> " else "  "
    SmartProfileExportSelectionState.ALL -> "[x] "
    SmartProfileExportSelectionState.PARTIAL -> "[~] "
    SmartProfileExportSelectionState.NONE -> "[ ] "
}

private fun profileSelectionColor(
    selectionState: SmartProfileExportSelectionState?,
    active: Boolean,
    selected: Boolean,
    colors: com.foxhole.guard.ui.cli.CliColors,
): Color = when {
    selectionState == null && active -> colors.ok
    selectionState == null -> colors.faint
    selected -> colors.accent
    else -> colors.faint
}

private const val PROFILE_TABLE_NAME_WEIGHT = 0.43f
private const val PROFILE_TABLE_PROTOCOL_WEIGHT = 0.25f
private const val PROFILE_TABLE_EXPIRY_WEIGHT = 0.32f
private const val PROFILE_TABLE_MARQUEE_DELAY_MS = 1_200
private const val PROFILE_TABLE_MARQUEE_REPEAT_MS = 1_000

private val PROFILE_TABLE_MARKER_GAP = 2.dp
