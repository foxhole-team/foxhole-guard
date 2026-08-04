package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foxhole.core.model.Profile
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliContextHelpButton
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.renameProfile

/**
 * The structured profile editor (replaces the old whole-config text dialog): an editable profile
 * name on top, then one collapsible form per protocol parsed out of the profile's configs. Every
 * form edit is applied to the parsed config tree in place, so `SAVE` writes back the very same
 * document with only the touched leaves changed — infra outbounds, dns/route/inbounds and unknown
 * keys survive verbatim. Enable/disable and add/remove change the profile's protocol *set* and
 * persist immediately (pending field edits are flushed first); the rest waits for `SAVE`.
 */
@Composable
internal fun CliProfileEditorScreen(
    viewModel: HomeViewModel,
    profile: Profile,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    val scope = rememberCoroutineScope()
    val messages =
        CliProfileEditorMessages(
            loadFailed = stringResource(R.string.cli_prof_edit_load_failed),
            lastProtocol = stringResource(R.string.cli_prof_edit_last_protocol),
        )
    val controller =
        remember(profile.id) {
            CliProfileEditorController(
                viewModel = viewModel,
                profileId = profile.id,
                scope = scope,
                messages = messages,
            )
        }
    LaunchedEffect(profile.id, controller.reloadKey) {
        controller.load(profile = profile, onUnreadable = onDismiss)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(colors.bg).padding(CliSpacing.md)) {
            CliProfileEditorHeader(viewModel = viewModel, profile = profile)
            CliProfileEditorList(
                viewModel = viewModel,
                profile = profile,
                controller = controller,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliProfileEditorFooter(
                dirty = controller.dirty,
                busy = controller.busy || controller.slots == null,
                onSave = { controller.save(onDismiss) },
                onCancel = onDismiss,
            )
        }
    }
}

@Composable
private fun CliProfileEditorHeader(
    viewModel: HomeViewModel,
    profile: Profile,
) {
    // Keyed on id alone: keying on name reset the set on every store re-emit after the first
    // rename. saveable so rotation does not eat a half-typed name.
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    CliScreenHeader(
        label = stringResource(R.string.cli_prof_edit_title),
        icon = R.drawable.pix_edit,
        trailing = { CliContextHelpButton(bodyRes = R.string.cli_help_editor_body) },
    )
    CliInputRow(
        prompt = stringResource(R.string.cli_prof_rename_prompt),
        value = name,
        onValueChange = { name = it },
        onSubmit = { viewModel.renameProfile(profile.id, name) },
        trailingChipLabel = stringResource(R.string.cli_prof_edit_rename),
        onTrailingChip = { viewModel.renameProfile(profile.id, name) },
    )
}

@Composable
private fun CliProfileEditorList(
    viewModel: HomeViewModel,
    profile: Profile,
    controller: CliProfileEditorController,
    modifier: Modifier,
) {
    val colors = LocalCliColors.current
    val slots = controller.slots
    if (slots == null) {
        Text(text = stringResource(R.string.cli_common_loading), style = CliType.body, color = colors.dim)
        Spacer(modifier = modifier)
        return
    }
    LazyColumn(modifier = modifier) {
        items(slots.protocolRefs(), key = { "${it.slotIndex}:${it.outboundIndex}" }) { ref ->
            val slot = slots[ref.slotIndex]
            val optionId = slot.optionId
            CliProfileEditorProtocolCard(
                outbound = slot.outboundAt(ref.outboundIndex),
                slotLabel = slot.label,
                enabled = profile.protocolEnabled(optionId),
                expanded = controller.expanded == ref,
                onToggleExpanded = { controller.toggleExpanded(ref) },
                onOutboundChange = { outbound -> controller.changeOutbound(ref, outbound) },
                onDelete = { controller.deleteProtocol(ref) },
                canToggleEnabled = optionId != null && profile.protocolOptions.size > 1,
                onToggleEnabled = {
                    optionId?.let { id -> controller.toggleEnabled(id, !profile.protocolEnabled(id)) }
                },
                modifier = Modifier.padding(bottom = CliSpacing.sm),
            )
        }
        item {
            CliProfileEditorAddPanel(
                viewModel = viewModel,
                profileId = profile.id,
                slots = slots,
                onAdd = controller::addProtocol,
            )
        }
    }
}

@Composable
private fun CliProfileEditorFooter(
    dirty: Boolean,
    busy: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalCliColors.current
    if (dirty) {
        CliElbowLine(text = stringResource(R.string.cli_prof_edit_dirty), color = colors.warn)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
        CliButton(
            label = stringResource(R.string.cli_prof_config_save),
            filled = true,
            enabled = !busy,
            onClick = onSave,
            modifier = Modifier.weight(1f),
        )
        CliButton(
            label = stringResource(R.string.cli_common_no_cancel),
            color = colors.err,
            enabled = !busy,
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun Profile.protocolEnabled(optionId: String?): Boolean =
    optionId == null || protocolOptions.firstOrNull { it.id == optionId }?.enabled != false
