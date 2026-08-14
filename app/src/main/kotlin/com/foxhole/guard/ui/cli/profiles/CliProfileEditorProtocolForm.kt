package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliOptionRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliSelectRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import kotlinx.serialization.json.JsonObject

/**
 * One protocol of the profile as a collapsible terminal panel: `type · tag` caption, the on/off and
 * delete chips, then the field form grouped common → auth → transport → tls. Only the fields the
 * outbound's own `type` can carry are rendered ([cliProtoFields]); a type without a form — and any
 * key the form does not model — stays reachable through the full-screen manual editor.
 */
@Composable
internal fun CliProfileEditorProtocolCard(
    outbound: JsonObject,
    slotLabel: String,
    enabled: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOutboundChange: (JsonObject) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    canToggleEnabled: Boolean = false,
    onToggleEnabled: () -> Unit = {},
) {
    val colors = LocalCliColors.current
    val type = outbound.cliOutboundType()
    CliPanel(
        icon = R.drawable.pix_shield,
        modifier = modifier.fillMaxWidth(),
        title = "$type · ${outbound.cliOutboundTag().ifBlank { slotLabel }}",
        titleColor = if (enabled) colors.accent else colors.faint,
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        CliProtocolCardActions(
            enabled = enabled,
            label = outbound.cliOutboundTag().ifBlank { slotLabel },
            canToggleEnabled = canToggleEnabled,
            onToggleEnabled = onToggleEnabled,
            onDelete = onDelete,
        )
        if (type in CLI_STRUCTURED_OUTBOUND_TYPES) {
            CliProtocolFieldGroups(outbound = outbound, type = type, onOutboundChange = onOutboundChange)
        } else {
            CliElbowLine(text = stringResource(R.string.cli_prof_edit_unstructured), color = colors.warn)
        }
    }
}

@Composable
private fun CliProtocolCardActions(
    enabled: Boolean,
    label: String,
    canToggleEnabled: Boolean,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalCliColors.current
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm, Alignment.End),
    ) {
        if (canToggleEnabled) {
            CliChip(
                label = stringResource(if (enabled) R.string.cli_prof_proto_on else R.string.cli_prof_proto_off),
                color = if (enabled) colors.ok else colors.dim,
                selected = enabled,
                onClick = onToggleEnabled,
            )
        }
        CliChip(
            label = stringResource(R.string.cli_prof_edit_delete),
            color = colors.err,
            onClick = { confirmDelete = true },
        )
    }
    // Dropping a protocol is destructive and irreversible from here, so it asks in the shared
    // bottom modal rather than swapping the delete chip for a pair of y/n chips under the finger.
    if (confirmDelete) {
        CliConfirmSheet(
            title = stringResource(R.string.cli_prof_edit_delete),
            icon = R.drawable.pix_trash,
            question = stringResource(R.string.cli_data_destructive_confirm, label),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun CliProtocolFieldGroups(
    outbound: JsonObject,
    type: String,
    onOutboundChange: (JsonObject) -> Unit,
) {
    val transportType = outbound.readCliProtoField(cliTransportTypeProbe)
    cliProtoFields(type, transportType)
        .groupBy(CliProtoField::group)
        .forEach { (group, fields) ->
            CliProtoGroupCaption(group = group)
            fields.forEach { field ->
                CliProtoFieldRow(
                    field = field,
                    value = outbound.readCliProtoField(field),
                    onChange = { value ->
                        onOutboundChange(
                            if (field.isTransportType()) {
                                outbound.writeCliTransportType(value)
                            } else {
                                outbound.writeCliProtoField(field, value)
                            },
                        )
                    },
                )
            }
        }
}

@Composable
private fun CliProtoGroupCaption(group: CliProtoGroup) {
    val colors = LocalCliColors.current
    val label =
        stringResource(
            when (group) {
                CliProtoGroup.COMMON -> R.string.cli_prof_edit_group_common
                CliProtoGroup.AUTH -> R.string.cli_prof_edit_group_auth
                CliProtoGroup.TRANSPORT -> R.string.cli_prof_edit_group_transport
                CliProtoGroup.TLS -> R.string.cli_prof_edit_group_tls
            },
        )
    Text(
        text = "── $label ──",
        style = CliType.small,
        color = colors.faint,
        modifier = Modifier.padding(top = CliSpacing.sm),
    )
}

@Composable
private fun CliProtoFieldRow(
    field: CliProtoField,
    value: String,
    onChange: (String) -> Unit,
) {
    when (field.kind) {
        CliProtoKind.BOOL ->
            CliToggleRow(
                label = field.label,
                checked = value == "true",
                onToggle = { checked -> onChange(checked.toString()) },
            )
        CliProtoKind.CHOICE -> CliProtoChoiceRow(field = field, value = value, onChange = onChange)
        else ->
            CliInputRow(
                prompt = field.label,
                value = value,
                onValueChange = onChange,
                numeric = field.kind == CliProtoKind.NUMBER,
            )
    }
}

@Composable
private fun CliProtoChoiceRow(
    field: CliProtoField,
    value: String,
    onChange: (String) -> Unit,
) {
    var expanded by remember(field.label) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        CliSelectRow(
            label = field.label,
            value = value.ifBlank { CLI_UNSET_CHOICE },
            expanded = expanded,
            onExpandToggle = { expanded = !expanded },
        )
        if (expanded) {
            field.choices.forEach { choice ->
                CliOptionRow(
                    text = choice.ifBlank { CLI_UNSET_CHOICE },
                    selected = choice == value,
                    onSelect = {
                        expanded = false
                        onChange(choice)
                    },
                )
            }
        }
    }
}

// Reading the current transport type needs a field descriptor; the catalog's own transport-type
// entry is not exported, so this probe mirrors its path.
private val cliTransportTypeProbe =
    CliProtoField(
        label = "transport.type",
        path = listOf("transport", "type"),
        kind = CliProtoKind.CHOICE,
        group = CliProtoGroup.TRANSPORT,
    )

private const val CLI_UNSET_CHOICE = "—"
