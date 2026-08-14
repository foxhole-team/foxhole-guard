package com.foxhole.guard.ui.cli.profiles

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.Profile
import com.foxhole.core.profile.exportableProfileChoices
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfilesExportSelectionState
import com.foxhole.guard.ui.ProfilesRouteUiState
import com.foxhole.guard.ui.SmartProfileExportSelectionState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliDisclosureGlyph
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliPixIcon
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
    // saveable: rotation must not silently close the editor — that discards unsaved edits.
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
            onDeleteCancel = { actions.changePendingDelete(null) },
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
            onShare = {
                // Share: select only this profile for export, replacing the set.
                actions.changePendingDelete(null)
                actions.changeSelection(
                    ProfilesExportSelectionState().selectedForExport(profile, expandSmart = true),
                )
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
                    // A live profile switch is confirmed (B2) before it reconnects; idle activates.
                    viewModel.onActivateProfileRequested(profile.id)
                },
                onDismiss = { detailOpen = false },
            )
        }
        if (editorOpen) {
            // Long-press -> edit opens the structural editor; manual config text stays on its own
            // full-height surface.
            CliProfileEditorScreen(
                viewModel = viewModel,
                profile = profile,
                onDismiss = { editorOpen = false },
            )
        }
        // C1: the smart-profile protocol table lives in a bottom sheet, driven by expandedSmartId
        // and opened by a whole-row tap (P1/item 1). Only smart profiles reach here.
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
        // Smart profiles raise the management sheet; a regular profile raises its detail sheet.
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
        // In selection mode long-press keeps its old checkbox behaviour.
        toggleSelection()
    } else {
        // Outside selection mode long-press opens the profile action row.
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = CliSpacing.xs, bottom = CliSpacing.xs),
    ) {
        choices.forEach { choice ->
            val selected = choice.selectionKey in selectedKeys
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 38.dp)
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
    onDeleteCancel: () -> Unit,
    onRenameSubmit: (String) -> Unit,
    onOpenEditor: () -> Unit,
    onSelectForExport: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = LocalCliColors.current
    val selected =
        selectionState != null && selectionState != SmartProfileExportSelectionState.NONE
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .selectedProfileDecoration(selected, colors.accent)
                .cliCombinedPressable(onClick = onTap, onLongClick = onLongPress),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileTableLeading(
                profile = profile,
                active = active,
                selected = selected,
                selectionState = selectionState,
            )
            Text(
                text = profile.name,
                style = CliType.body,
                color = if (active) colors.fg else colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .weight(PROFILE_TABLE_NAME_WEIGHT)
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = PROFILE_TABLE_MARQUEE_DELAY_MS,
                        repeatDelayMillis = PROFILE_TABLE_MARQUEE_REPEAT_MS,
                    ),
            )
            Text(
                text = profileTableProtocolLabel(profile),
                style = CliType.small,
                color = if (profile.requiresInsecureTls) colors.warn else colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(PROFILE_TABLE_PROTOCOL_WEIGHT),
            )
            Text(
                text = profile.subscriptionExpiresAt?.let(::formatExpiryDate) ?: "—",
                style = CliType.small,
                color = if (profile.subscriptionExpiresAt == null) colors.faint else colors.warn,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(PROFILE_TABLE_EXPIRY_WEIGHT),
            )
            // A smart profile is marked with the canonical disclosure arrow; tapping it opens
            // the protocol table without replacing the three stable columns above.
            if (profile.protocolOptions.size > 1) {
                Spacer(modifier = Modifier.width(6.dp))
                CliDisclosureGlyph(expanded = smartExpanded, color = colors.accent)
            } else {
                Spacer(modifier = Modifier.width(PROFILE_TABLE_DISCLOSURE_WIDTH))
            }
        }
        // Long-press reveals the action row: rename inline, edit, delete, select, share.
        if (deleteArmed) {
            CliProfileActionsRow(
                profileName = profile.name,
                onRenameSubmit = onRenameSubmit,
                onOpenEditor = onOpenEditor,
                onDeleteConfirm = onDeleteConfirm,
                onSelectForExport = onSelectForExport,
                onShare = onShare,
                onDismiss = onDeleteCancel,
            )
        }
    }
}

@Composable
private fun ProfileTableLeading(
    profile: Profile,
    active: Boolean,
    selected: Boolean,
    selectionState: SmartProfileExportSelectionState?,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.width(PROFILE_TABLE_LEADING_WIDTH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(PROFILE_TABLE_CURSOR_WIDTH),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (selectionState == null) {
                if (active) {
                    CliPixIcon(
                        id = R.drawable.pix_arrow_right,
                        contentDescription = null,
                        size = 12.dp,
                        tint = profileSelectionColor(selectionState, active, selected, colors),
                    )
                }
            } else {
                Text(
                    text = profileSelectionMarker(selectionState, active).trimEnd(),
                    style = CliType.body,
                    color = profileSelectionColor(selectionState, active, selected, colors),
                )
            }
        }
        Box(
            modifier = Modifier.width(PROFILE_TABLE_FLAG_WIDTH),
            contentAlignment = Alignment.Center,
        ) {
            profileCountryCode(
                profile.protocolOptions.firstOrNull { it.isSelected }?.displayName,
                profile.name,
            )?.let { country -> CliFlagIcon(countryCode = country) }
        }
    }
}

/** Header shared by the profile list's fixed name / protocol / expiry columns. */
@Composable
internal fun CliProfileTableHeader() {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(PROFILE_TABLE_LEADING_WIDTH))
        Text(
            text = stringResource(R.string.cli_home_key_profile),
            style = CliType.small,
            color = colors.faint,
            modifier = Modifier.weight(PROFILE_TABLE_NAME_WEIGHT),
        )
        Text(
            text = stringResource(R.string.cli_home_key_protocol),
            style = CliType.small,
            color = colors.faint,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(PROFILE_TABLE_PROTOCOL_WEIGHT),
        )
        Text(
            text = stringResource(R.string.cli_prof_facts_expiry),
            style = CliType.small,
            color = colors.faint,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(PROFILE_TABLE_EXPIRY_WEIGHT),
        )
        Spacer(modifier = Modifier.width(PROFILE_TABLE_DISCLOSURE_WIDTH + 6.dp))
    }
}

internal fun profileTableProtocolLabel(profile: Profile): String {
    val selected = profile.protocolOptions.firstOrNull { it.id == profile.selectedProtocolOptionId }
        ?: profile.protocolOptions.firstOrNull { it.isSelected }
    return (selected?.protocolHint ?: profile.protocolHint).name.lowercase()
}

private enum class CliProfileActionStage { MENU, RENAME, DELETE }

// The action row stays on screen while the delete question is up: the modal is the only thing
// that changes, so the chips do not shuffle under the finger that opened them.
private val CliProfileActionStage.showsMenu: Boolean
    get() = this != CliProfileActionStage.RENAME

@Composable
private fun CliProfileActionsRow(
    profileName: String,
    onRenameSubmit: (String) -> Unit,
    onOpenEditor: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onSelectForExport: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    var stage by remember(profileName) { mutableStateOf(CliProfileActionStage.MENU) }
    var nameText by remember(profileName) { mutableStateOf(profileName) }
    // A SECTION, not a chip strip: the marching-ants frame of the home profile selector marks
    // the live area, pushing the next profile down. Actions are pure glyphs, centred — the
    // colour code carries the words (destructive red, editor blue).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 6.dp)
            .cliMarchingBorder(colors.accent)
            .padding(CliSpacing.xs),
    ) {
        if (stage.showsMenu) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CliProfileActionGlyph(
                    icon = R.drawable.pix_edit,
                    contentDescription = stringResource(R.string.cli_prof_act_rename),
                    tint = colors.fg,
                    onClick = { stage = CliProfileActionStage.RENAME },
                )
                CliProfileActionGlyph(
                    icon = R.drawable.pix_settings,
                    contentDescription = stringResource(R.string.cli_prof_act_edit),
                    tint = colors.info,
                    onClick = onOpenEditor,
                )
                CliProfileActionGlyph(
                    icon = R.drawable.pix_export,
                    contentDescription = stringResource(R.string.cli_prof_act_share),
                    tint = colors.accent,
                    onClick = onShare,
                )
                CliProfileActionGlyph(
                    icon = R.drawable.pix_check,
                    contentDescription = stringResource(R.string.cli_prof_act_select),
                    tint = colors.ok,
                    onClick = onSelectForExport,
                )
                CliProfileActionGlyph(
                    icon = R.drawable.pix_trash,
                    contentDescription = stringResource(R.string.cli_prof_act_delete),
                    tint = colors.err,
                    onClick = { stage = CliProfileActionStage.DELETE },
                )
                CliProfileActionGlyph(
                    icon = R.drawable.pix_cross,
                    contentDescription = stringResource(R.string.cli_common_no_cancel),
                    tint = colors.err,
                    onClick = onDismiss,
                )
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

/** One glyph action of the profile section: a 48dp target around a 16dp pixel icon. */
@Composable
private fun CliProfileActionGlyph(
    @DrawableRes icon: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .cliPressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CliPixIcon(id = icon, contentDescription = contentDescription, tint = tint)
    }
}

// Export selection is a rectangular pixel frame: a crisp 1dp dimmed-accent border over a light
// fill, matching the panel frames — no rounding, no half-tones.
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
    // The null branch is dead for text (CliProfileRow draws the cursor); kept to exhaust the when.
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
private val PROFILE_TABLE_CURSOR_WIDTH = 20.dp
private val PROFILE_TABLE_FLAG_WIDTH = 24.dp
private val PROFILE_TABLE_LEADING_WIDTH = PROFILE_TABLE_CURSOR_WIDTH + PROFILE_TABLE_FLAG_WIDTH
private val PROFILE_TABLE_DISCLOSURE_WIDTH = 12.dp
