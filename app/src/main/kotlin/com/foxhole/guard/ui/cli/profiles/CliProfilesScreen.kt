package com.foxhole.guard.ui.cli.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfileImportConfirmationState
import com.foxhole.guard.ui.ProfilesExportSelectionState
import com.foxhole.guard.ui.ProfilesExportSelectionStateSaver
import com.foxhole.guard.ui.ProfilesRouteUiState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliContextHelpButton
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.cli.home.CliTerminalState
import com.foxhole.guard.ui.cli.home.CliTorPromptPanel
import com.foxhole.guard.ui.cli.home.modeCommandFor
import com.foxhole.guard.ui.createProfileFromTemplate
import com.foxhole.guard.ui.isReady
import com.foxhole.guard.ui.protocolOptionOrDefault
import com.foxhole.guard.ui.pruneTo
import com.foxhole.guard.ui.requests
import kotlinx.coroutines.launch

/**
 * Profiles: tap selects, long-press marks rows for export (long-press an already marked
 * row to arm the inline delete y/n line). While a selection is alive the symmetric
 * file/qr/clipboard row flips from import to blinking export actions; system back or
 * unmarking the last row leaves the mode. Smart-profile protocol management (the chip) opens
 * [CliSmartProfileSheet]; switching the active profile/protocol on a live tunnel confirms via the
 * shared [CliTorPromptPanel] (B2/B3).
 */
@Composable
internal fun CliProfilesScreen(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.profilesRouteState.collectAsStateWithLifecycle()
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
    val importConfirmation by viewModel.profileImportConfirmation.collectAsStateWithLifecycle()
    // saveable, paired with selection below: rotation must not disarm a delete or fold an open
    // smart sheet.
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var expandedSmartId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Export selection survives process death: ids/keys ride the existing saver from the
    // classic export sheet.
    var selection by rememberSaveable(stateSaver = ProfilesExportSelectionStateSaver) {
        mutableStateOf(ProfilesExportSelectionState())
    }
    val selectionMode = selection.isReady()

    // A vanished profile id must not keep the confirm line armed or a stale selection.
    LaunchedEffect(state.profiles) {
        if (pendingDeleteId != null && state.profiles.none { it.id == pendingDeleteId }) {
            pendingDeleteId = null
        }
        // Write only on a real change: the effect restarts on every list emit, and an
        // unconditional assignment would recompose selection subscribers for nothing.
        val pruned = selection.pruneTo(state.profiles)
        if (pruned != selection) {
            selection = pruned
        }
    }

    BackHandler(enabled = selectionMode || pendingDeleteId != null || expandedSmartId != null) {
        when {
            expandedSmartId != null -> expandedSmartId = null
            pendingDeleteId != null -> pendingDeleteId = null
            else -> selection = ProfilesExportSelectionState()
        }
    }

    CliProfilesBody(
        viewModel = viewModel,
        terminal = terminal,
        state = state,
        home = home,
        importConfirmation = importConfirmation,
        selection = selection,
        pendingDeleteId = pendingDeleteId,
        expandedSmartId = expandedSmartId,
        actions = CliProfilesActions(
            changeSelection = { selection = it },
            changePendingDelete = { pendingDeleteId = it },
            changeExpandedSmart = { expandedSmartId = it },
        ),
        modifier = modifier,
    )
}

@Composable
private fun CliProfilesBody(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    state: ProfilesRouteUiState,
    home: HomeRouteUiState,
    importConfirmation: ProfileImportConfirmationState?,
    selection: ProfilesExportSelectionState,
    pendingDeleteId: Long?,
    expandedSmartId: Long?,
    actions: CliProfilesActions,
    modifier: Modifier,
) {
    val selectedCount = selection.selectedKeyCount().takeIf { selection.isReady() } ?: 0
    // Template add is always one tap from the top: pick a protocol template, continue into the
    // empty editor of the created profile. Links/files/QR keep their own import row below.
    var templateAddOpen by rememberSaveable { mutableStateOf(false) }
    var templateBusy by remember { mutableStateOf(false) }
    var templateEditorProfile by remember { mutableStateOf<com.foxhole.core.model.Profile?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier
            .fillMaxSize()
            // Blank/background taps dismiss the one contextual action strip. Child controls keep
            // their own gesture and explicitly clear it below, so their intended action still runs.
            .pointerInput(pendingDeleteId) {
                if (pendingDeleteId != null) {
                    detectTapGestures { actions.changePendingDelete(null) }
                }
            }
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_prof_title),
            icon = R.drawable.pix_profiles,
            suffix = selectedCount.takeIf { it > 0 }?.let { stringResource(R.string.cli_prof_sel_count, it) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CliProfileAddButton(
                        onClick = {
                            actions.changePendingDelete(null)
                            templateAddOpen = true
                        },
                    )
                    CliContextHelpButton(
                        bodyRes = R.string.cli_help_editor_body,
                        onOpen = { actions.changePendingDelete(null) },
                    )
                }
            },
        )
        if (templateAddOpen) {
            CliProfileTemplateSheet(
                // Its own title, not the import dialog's: this sheet creates a blank profile, it
                // does not import one, and the two used to share a caption that asked a question
                // about a payload there is none of here.
                title = stringResource(R.string.cli_prof_create_title),
                busy = templateBusy,
                onDismiss = { templateAddOpen = false },
                onContinue = { type ->
                    if (!templateBusy) {
                        templateBusy = true
                        scope.launch {
                            val created = viewModel.createProfileFromTemplate(type)
                            templateBusy = false
                            templateAddOpen = false
                            if (created != null) {
                                templateEditorProfile = created
                            }
                        }
                    }
                },
            )
        }
        templateEditorProfile?.let { created ->
            CliProfileEditorScreen(
                viewModel = viewModel,
                profile = created,
                onDismiss = { templateEditorProfile = null },
                // The protocol template was chosen one step earlier. A second "add protocol"
                // inside a brand-new profile duplicated that choice and made a simple creation
                // flow look like a smart-profile builder.
                allowAddProtocol = false,
            )
        }
        if (home.activeProfile != null) {
            CliActiveProfileFactsPanel(home = home)
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
        // The import confirmation is a modal now, so it takes no room in the column and needs no
        // divider of its own.
        importConfirmation?.let { confirmation ->
            CliImportConfirmPanel(viewModel = viewModel, confirmation = confirmation)
        }
        CliProfilesPanel(
            viewModel = viewModel,
            state = state,
            selection = selection,
            pendingDeleteId = pendingDeleteId,
            expandedSmartId = expandedSmartId,
            actions = actions,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        // Switch confirmation is the shared bottom sheet and does not move the layout.
        home.torTransitionPrompt?.let { prompt ->
            CliTorPromptPanel(
                viewModel = viewModel,
                prompt = prompt,
                onLiveModeSwitchConfirmed = { target ->
                    terminal.command(modeCommandFor(target))
                },
            )
        }
        CliProfileTransferRow(
            viewModel = viewModel,
            selection = selection,
            onSelectionCleared = { actions.changeSelection(ProfilesExportSelectionState()) },
            onInteraction = { actions.changePendingDelete(null) },
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

/**
 * Facts about the active profile: profile and protocol, kind (subscription or config) and, when a
 * subscription has one, its expiry. This replaced the old status line — the profiles screen
 * describes the profile, not the connection.
 */
@Composable
private fun CliActiveProfileFactsPanel(home: HomeRouteUiState) {
    val colors = LocalCliColors.current
    val profile = home.activeProfile ?: return
    val activeProtocol =
        profile.protocolOptionOrDefault(home.connection.protocolOptionId)?.displayName
            ?: profile.protocolHint.name.lowercase()
    val isSubscription =
        profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL || profile.protocolOptions.size > 1
    CliPanel(modifier = Modifier.fillMaxWidth()) {
        CliKeyValue(
            key = stringResource(R.string.cli_prof_facts_profile),
            value = profile.name,
            valueColor = colors.fg,
            icon = R.drawable.pix_profiles,
        )
        CliKeyValue(
            key = stringResource(R.string.cli_prof_facts_protocol),
            value = activeProtocol,
            valueColor = colors.info,
            icon = R.drawable.pix_shield,
        )
        // Geo inferred from the node/profile name; the row appears only when the name revealed it.
        profileCountryCode(activeProtocol, profile.name)?.let { country ->
            CliKeyValue(
                key = stringResource(R.string.cli_prof_facts_geo),
                value = country.uppercase(),
                valueColor = colors.fg,
                icon = R.drawable.pix_map,
                valueLeading = { CliFlagIcon(countryCode = country) },
            )
        }
        CliKeyValue(
            key = stringResource(R.string.cli_prof_facts_type),
            value = stringResource(
                if (isSubscription) R.string.cli_prof_facts_type_sub else R.string.cli_prof_facts_type_config,
            ),
            valueColor = colors.fg,
            icon = if (isSubscription) R.drawable.pix_import else R.drawable.pix_edit,
        )
        profile.subscriptionExpiresAt?.let { expiresAt ->
            CliKeyValue(
                key = stringResource(R.string.cli_prof_facts_expiry),
                value = formatExpiryDate(expiresAt),
                valueColor = colors.warn,
                icon = R.drawable.pix_clock,
            )
        }
    }
}

@Composable
private fun CliProfilesPanel(
    viewModel: HomeViewModel,
    state: ProfilesRouteUiState,
    selection: ProfilesExportSelectionState,
    pendingDeleteId: Long?,
    expandedSmartId: Long?,
    actions: CliProfilesActions,
    modifier: Modifier,
) {
    CliPanel(
        icon = R.drawable.pix_profiles,
        title = stringResource(R.string.cli_prof_title),
        modifier = modifier,
    ) {
        CliProfilesEmptyState(state)
        if (state.profiles.isNotEmpty()) {
            CliProfileTableHeader()
            CliRowDivider()
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(state.profiles, key = { _, profile -> profile.id }) { index, profile ->
                if (index > 0) CliRowDivider()
                CliProfileListItem(viewModel, state, profile, selection, pendingDeleteId, expandedSmartId, actions)
            }
        }
    }
}

@Composable
private fun CliProfilesEmptyState(state: ProfilesRouteUiState) {
    val text = when {
        !state.profilesLoaded -> stringResource(R.string.cli_common_loading_data)
        state.profiles.isEmpty() -> stringResource(R.string.cli_prof_empty)
        else -> return
    }
    Text(
        text = text,
        style = CliType.body,
        color = LocalCliColors.current.dim,
        modifier = Modifier.padding(vertical = 10.dp),
    )
}

/** The header's always-there plus: manual profile add, one tap from anywhere on the tab. */
@Composable
private fun CliProfileAddButton(onClick: () -> Unit) {
    val colors = LocalCliColors.current
    Box(
        // requiredSize: the 48dp target overlaps the fixed header slot without inflating the row
        // (the statistics gear law).
        modifier = Modifier
            .requiredSize(48.dp)
            .cliPressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CliPixIcon(
            id = R.drawable.pix_add,
            contentDescription = stringResource(R.string.cli_prof_edit_add),
            tint = colors.info,
        )
    }
}

internal data class CliProfilesActions(
    val changeSelection: (ProfilesExportSelectionState) -> Unit,
    val changePendingDelete: (Long?) -> Unit,
    val changeExpandedSmart: (Long?) -> Unit,
)

// Shared between the header suffix count (this file) and the transfer row (CliProfileTransfer.kt).
internal fun ProfilesExportSelectionState.selectedKeyCount(): Int =
    requests().sumOf { request -> request.selectionKeys.size }
