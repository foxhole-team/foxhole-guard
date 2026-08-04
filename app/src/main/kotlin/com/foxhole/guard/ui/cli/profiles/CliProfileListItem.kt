package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliDisclosureGlyph
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.cliCombinedPressable
import com.foxhole.guard.ui.deleteProfile
import com.foxhole.guard.ui.isReady
import com.foxhole.guard.ui.onActivateProfileRequested
import com.foxhole.guard.ui.renameProfile
import com.foxhole.guard.ui.smartProfileSelectionState
import com.foxhole.guard.ui.toggleSingleProfile
import com.foxhole.guard.ui.toggleSmartProfileAll

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
            smartExpanded = expandedSmartId == profile.id,
            selectionState = rowSelection,
            onTap = {
                when {
                    selectionMode -> toggleSelection()
                    pendingDeleteId != null -> actions.changePendingDelete(null)
                    // Smart profiles (>1 protocol) raise the management sheet on a whole-row tap;
                    // a regular single-protocol profile raises its own detail sheet (table + Test +
                    // Activate) from the same bottom edge.
                    isSmart -> actions.changeExpandedSmart(profile.id)
                    else -> detailOpen = true
                }
            },
            onLongPress = {
                if (selectionMode) {
                    // In selection mode long-press keeps its old checkbox behaviour.
                    toggleSelection()
                } else {
                    // Outside selection mode long-press opens the profile action row.
                    actions.changePendingDelete(profile.id)
                }
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
                actions.changeSelection(selection.toggledProfile(profile))
            },
            onShare = {
                // Share: select only this profile for export, replacing the set.
                actions.changePendingDelete(null)
                actions.changeSelection(ProfilesExportSelectionState().toggledProfile(profile))
            },
        )
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
            // Long-press -> edit opens the structural editor; raw JSON stays inside each
            // protocol's expander.
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

internal fun ProfilesExportSelectionState.toggledProfile(profile: Profile): ProfilesExportSelectionState =
    if (exportableProfileChoices(profile).size > 1) {
        toggleSmartProfileAll(profile)
    } else {
        toggleSingleProfile(profile)
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
            // Outside export mode the active profile carries a pixel cursor; in selection mode
            // the ASCII checkboxes stay.
            if (selectionState == null) {
                if (active) {
                    CliPixIcon(
                        id = R.drawable.pix_arrow_right,
                        contentDescription = null,
                        size = 12.dp,
                        tint = profileSelectionColor(selectionState, active, selected, colors),
                    )
                } else {
                    Spacer(modifier = Modifier.width(CliSpacing.md))
                }
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Text(
                    text = profileSelectionMarker(selectionState, active),
                    style = CliType.body,
                    color = profileSelectionColor(selectionState, active, selected, colors),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Geo flag inferred from the node/profile name: the model carries no country,
                    // but subscription names almost always reveal it. No code, no flag.
                    profileCountryCode(
                        profile.protocolOptions.firstOrNull { it.isSelected }?.displayName,
                        profile.name,
                    )?.let { country ->
                        CliFlagIcon(countryCode = country)
                        Spacer(modifier = Modifier.width(CliSpacing.xs))
                    }
                    Text(
                        text = profile.name,
                        style = CliType.body,
                        color = if (active) colors.fg else colors.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(text = profileDescriptor(profile), style = CliType.small, color = colors.faint, maxLines = 1)
            }
            // A smart profile is marked with the CliSelectRow disclosure arrow and no protocol
            // count: the sheet opened by tapping the row shows that table.
            if (profile.protocolOptions.size > 1) {
                Text(
                    text = stringResource(R.string.cli_prof_smart_label),
                    style = CliType.small,
                    color = colors.faint,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(6.dp))
                CliDisclosureGlyph(expanded = smartExpanded, color = colors.accent)
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

private enum class CliProfileActionStage { MENU, RENAME, DELETE }

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
    when (stage) {
        CliProfileActionStage.MENU -> FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = CliSpacing.lg, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(CliSpacing.xs),
        ) {
            CliChip(
                label = stringResource(R.string.cli_prof_act_rename),
                onClick = { stage = CliProfileActionStage.RENAME },
            )
            CliChip(
                label = stringResource(R.string.cli_prof_act_edit),
                onClick = onOpenEditor,
            )
            CliChip(
                label = stringResource(R.string.cli_prof_act_delete),
                color = colors.err,
                onClick = { stage = CliProfileActionStage.DELETE },
            )
            CliChip(
                label = stringResource(R.string.cli_prof_act_select),
                onClick = onSelectForExport,
            )
            CliChip(
                label = stringResource(R.string.cli_prof_act_share),
                color = colors.info,
                onClick = onShare,
            )
            CliChip(label = "x", onClick = onDismiss)
        }
        CliProfileActionStage.RENAME -> CliInputRow(
            prompt = stringResource(R.string.cli_prof_rename_prompt),
            value = nameText,
            onValueChange = { nameText = it },
            onSubmit = { onRenameSubmit(nameText) },
            autoFocus = true,
            modifier = Modifier.padding(start = CliSpacing.lg),
        )
        CliProfileActionStage.DELETE -> CliProfileDeleteRow(
            profileName = profileName,
            onDeleteConfirm = onDeleteConfirm,
            onDeleteCancel = { stage = CliProfileActionStage.MENU },
        )
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

// Row subtitle: protocol and the TLS warning. The protocol count is deliberately absent — the
// disclosure arrow marks a smart profile instead.
private fun profileDescriptor(profile: Profile): String = buildString {
    append(profile.protocolHint.name.lowercase())
    if (profile.requiresInsecureTls) append(" · insecure-tls")
}

@Composable
private fun CliProfileDeleteRow(
    profileName: String,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = CliSpacing.lg, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.cli_prof_delete_confirm, profileName),
            style = CliType.small,
            color = colors.err,
            modifier = Modifier.weight(1f),
        )
        CliChip(label = "y", color = colors.err, onClick = onDeleteConfirm)
        CliChip(label = "n", onClick = onDeleteCancel)
    }
}
