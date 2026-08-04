package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.addProfileProtocolOption
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliOptionRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliSelectRow
import com.foxhole.guard.ui.emitError
import com.foxhole.guard.ui.parseProtocolShareUri
import kotlinx.serialization.json.JsonObject

/**
 * The `add protocol` block at the bottom of the editor. Two ways in, both ending as one more
 * protocol option on the profile: a pasted share URI goes through the importer (so it is parsed and
 * validated exactly like a normal import), while a blank protocol of a chosen type starts from the
 * profile's own infrastructure ([cliNewProtocolConfig]) with empty credentials to fill in.
 */
@Composable
internal fun CliProfileEditorAddPanel(
    viewModel: HomeViewModel,
    profileId: Long,
    slots: List<CliEditorSlot>,
    onAdd: (suspend (List<CliEditorSlot>) -> Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    var expanded by remember { mutableStateOf(false) }
    var uri by remember { mutableStateOf("") }
    var blankType by remember { mutableStateOf(CLI_NEW_OUTBOUND_TYPES.first()) }
    var typeExpanded by remember { mutableStateOf(false) }
    val addFailed = stringResource(R.string.cli_prof_edit_add_failed)
    CliPanel(
        icon = R.drawable.pix_add,
        modifier = modifier.fillMaxWidth(),
        title = stringResource(R.string.cli_prof_edit_add),
        titleColor = colors.info,
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = { expanded = !expanded },
    ) {
        CliInputRow(
            prompt = stringResource(R.string.cli_prof_edit_add_uri),
            value = uri,
            onValueChange = { uri = it },
            trailingChipLabel = stringResource(R.string.cli_prof_edit_add_apply),
            onTrailingChip = {
                val pasted = uri.trim()
                if (pasted.isNotEmpty()) {
                    uri = ""
                    onAdd { _ -> viewModel.addFromShareUri(profileId, pasted, addFailed) }
                }
            },
        )
        CliSelectRow(
            label = stringResource(R.string.cli_prof_edit_add_type),
            value = blankType,
            expanded = typeExpanded,
            onExpandToggle = { typeExpanded = !typeExpanded },
        )
        if (typeExpanded) {
            CLI_NEW_OUTBOUND_TYPES.forEach { type ->
                CliOptionRow(
                    text = type,
                    selected = type == blankType,
                    onSelect = {
                        typeExpanded = false
                        blankType = type
                    },
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = CliSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
        ) {
            CliChip(
                label = stringResource(R.string.cli_prof_edit_add_blank),
                color = colors.info,
                enabled = slots.isNotEmpty(),
                onClick = {
                    val type = blankType
                    onAdd { current -> viewModel.addBlankProtocol(profileId, type, current) }
                },
            )
        }
    }
}

private suspend fun HomeViewModel.addFromShareUri(
    profileId: Long,
    shareUri: String,
    failureMessage: String,
): Boolean {
    val parsed =
        runCatching { parseProtocolShareUri(shareUri) }
            .getOrElse {
                emitError(failureMessage)
                return false
            }
    return addProfileProtocolOption(
        profileId = profileId,
        displayName = parsed.displayName,
        protocolHint = parsed.protocolHint,
        configJson = parsed.configJson,
    )
}

private suspend fun HomeViewModel.addBlankProtocol(
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
