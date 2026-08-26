package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foxhole.core.model.Profile
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.addProfileProtocolOption
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.LocalCliType
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliModalCloseButton
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.cliModalSurfaceColor
import kotlinx.serialization.json.JsonObject

@Composable
internal fun CliProfileEditorScreen(
    viewModel: HomeViewModel,
    profile: Profile,
    onDismiss: () -> Unit,
    allowAddProtocol: Boolean = true,
) {
    val colors = LocalCliColors.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
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
    var templateSheetOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(profile.id, controller.reloadKey) {
        controller.load(profile = profile, onUnreadable = onDismiss)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val manualRef = controller.rawEditor
            if (manualRef != null) {
                CliManualProfileEditor(
                    initialText = controller.manualConfigText(manualRef),
                    busy = controller.busy,
                    onCancel = controller::closeManualEditor,
                    onSave = { text ->
                        controller.saveManualConfig(
                            profileName = name,
                            ref = manualRef,
                            text = text,
                            onSaved = onDismiss,
                        )
                    },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(cliModalSurfaceColor(LocalCliPanelAppearance.current, colors.panel))
                        .padding(CliSpacing.md),
                ) {
                    CliProfileEditorHeader(
                        name = name,
                        onNameChange = { name = it },
                        creating = !allowAddProtocol,
                    )
                    CliProfileEditorList(
                        profile = profile,
                        controller = controller,
                        onAddProtocol = if (allowAddProtocol) {
                            { templateSheetOpen = true }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    Spacer(modifier = Modifier.height(CliSpacing.sm))
                    CliProfileEditorFooter(
                        dirty = cliProfileEditorHasChanges(profile.name, name, controller.slots),
                        busy = controller.busy || controller.slots == null,
                        onSave = { controller.save(profileName = name, onSaved = onDismiss) },
                        onOpenManual = controller::openManualEditor,
                        onCancel = onDismiss,
                    )
                }
            }
        }
        if (allowAddProtocol && templateSheetOpen) {
            CliProfileTemplateSheet(
                title = stringResource(R.string.cli_prof_edit_add),
                busy = controller.busy,
                onDismiss = { templateSheetOpen = false },
                onContinue = { type ->
                    templateSheetOpen = false
                    controller.addProtocol { current ->
                        viewModel.addBlankProtocolOption(profile.id, type, current)
                    }
                },
            )
        }
    }
}

@Composable
private fun CliProfileEditorHeader(
    name: String,
    onNameChange: (String) -> Unit,
    creating: Boolean,
) {
    val colors = LocalCliColors.current
    CliScreenHeader(
        label = stringResource(
            if (creating) R.string.cli_prof_create_title else R.string.cli_prof_edit_title,
        ),
        icon = R.drawable.lin_edit,
        titleColor = colors.fg,
    )
    CliInputRow(
        prompt = stringResource(R.string.cli_prof_rename_prompt),
        value = name,
        onValueChange = onNameChange,
    )
}

private suspend fun HomeViewModel.addBlankProtocolOption(
    profileId: Long,
    type: String,
    slots: List<CliEditorSlot>,
): Boolean {
    val template = slots.firstOrNull()?.root ?: return false
    val config = cliNewProtocolConfig(template, cliBlankOutbound(type))
    return addProfileProtocolOption(
        profileId = profileId,
        displayName = type,
        protocolHint = cliProtocolHintForType(type),
        configJson = cliEditorJson.encodeToString(JsonObject.serializer(), config),
    )
}

@Composable
private fun CliProfileEditorList(
    profile: Profile,
    controller: CliProfileEditorController,
    onAddProtocol: (() -> Unit)?,
    modifier: Modifier,
) {
    val colors = LocalCliColors.current
    val slots = controller.slots
    if (slots == null) {
        Text(text = stringResource(R.string.cli_common_loading_data), style = CliType.body, color = colors.dim)
        Spacer(modifier = modifier)
        return
    }
    LazyColumn(modifier = modifier) {
        items(slots.protocolRefs(), key = { "${it.slotIndex}:${it.endpoint}:${it.entryIndex}" }) { ref ->
            val slot = slots[ref.slotIndex]
            CliProfileEditorProtocolCard(
                outbound = slot.entryAt(ref),
                slotLabel = slot.label,
                enabled = profile.protocolEnabled(slot.optionId),
                expanded = controller.expanded == ref,
                onToggleExpanded = { controller.toggleExpanded(ref) },
                onOutboundChange = { outbound -> controller.changeOutbound(ref, outbound) },
                onDelete = { controller.deleteProtocol(ref) },
                modifier = Modifier.padding(bottom = CliSpacing.sm),
            )
        }
        if (onAddProtocol != null) {
            item(key = "add_protocol") {
                CliButton(
                    label = stringResource(R.string.cli_prof_edit_add),
                    icon = R.drawable.lin_add,
                    color = colors.info,
                    enabled = !controller.busy,
                    onClick = onAddProtocol,
                    modifier = Modifier.fillMaxWidth().padding(bottom = CliSpacing.sm),
                )
            }
        }
    }
}

@Composable
private fun CliProfileEditorFooter(
    dirty: Boolean,
    busy: Boolean,
    onSave: () -> Unit,
    onOpenManual: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliEditorControlTypography {
        if (dirty) {
            CliElbowLine(text = stringResource(R.string.cli_prof_edit_dirty), color = colors.warn)
            CliButton(
                label = stringResource(R.string.cli_prof_config_save_action),
                color = colors.ok,
                iconContent = { tint -> CliDisketteIcon(tint = tint) },
                enabled = !busy,
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
        ) {
            CliModalCloseButton(
                enabled = !busy,
                dimWhenDisabled = false,
                onClick = onCancel,
                modifier = Modifier.weight(EDITOR_CANCEL_WEIGHT),
            )
            CliButton(
                label = stringResource(R.string.cli_prof_edit_raw),
                color = colors.accent,
                icon = R.drawable.lin_edit,
                enabled = !busy,
                dimWhenDisabled = false,
                onClick = onOpenManual,
                modifier = Modifier.weight(EDITOR_PHRASE_WEIGHT),
            )
        }
    }
}

@Composable
internal fun CliEditorControlTypography(content: @Composable () -> Unit) {
    val type = LocalCliType.current
    val scoped = remember(type) { type.copy(button = type.button.scaledBy(EDITOR_BUTTON_STEP_RATIO)) }
    CompositionLocalProvider(LocalCliType provides scoped, content = content)
}

internal fun TextStyle.scaledBy(ratio: Float): TextStyle =
    copy(fontSize = fontSize * ratio, lineHeight = lineHeight * ratio)

private const val EDITOR_BUTTON_STEP_RATIO = 0.82f

private const val EDITOR_CANCEL_WEIGHT = 1f
private const val EDITOR_PHRASE_WEIGHT = 2f

@Suppress("MagicNumber")
@Composable
internal fun CliDisketteIcon(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val pixel = size.minDimension / DISKETTE_GRID
        fun pixels(x: Int, y: Int, width: Int, height: Int) {
            drawRect(
                color = tint,
                topLeft = Offset(x * pixel, y * pixel),
                size = Size(width * pixel, height * pixel),
            )
        }
        pixels(2, 1, 9, 1)
        pixels(1, 2, 1, 12)
        pixels(11, 2, 2, 2)
        pixels(13, 4, 1, 9)
        pixels(2, 14, 11, 1)
        pixels(4, 2, 6, 1)
        pixels(4, 3, 1, 4)
        pixels(9, 3, 1, 4)
        pixels(5, 6, 4, 1)
        pixels(3, 9, 8, 1)
        pixels(3, 10, 1, 4)
        pixels(10, 10, 1, 4)
        pixels(4, 13, 6, 1)
    }
}

private fun Profile.protocolEnabled(optionId: String?): Boolean =
    optionId == null || protocolOptions.firstOrNull { it.id == optionId }?.enabled != false

private const val DISKETTE_GRID = 16f
