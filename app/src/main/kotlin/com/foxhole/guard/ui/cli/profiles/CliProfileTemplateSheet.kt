package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliOptionRow
import com.foxhole.guard.ui.cli.components.CliSelectRow
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow

/**
 * The VPN template picker: one dropdown of protocol templates and the canonical cancel/continue
 * row. The profiles tab uses it to create a new blank profile of the chosen type; the editor uses
 * it to add one more blank protocol to the profile being edited. [busy] disables continue while
 * the creation is in flight, so a slow write cannot be double-submitted.
 */
@Composable
internal fun CliProfileTemplateSheet(
    title: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onContinue: (String) -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(CLI_NEW_OUTBOUND_TYPES.first()) }
    var typeExpanded by rememberSaveable { mutableStateOf(false) }
    CliBottomSheet(
        onDismiss = onDismiss,
        title = title,
        icon = R.drawable.pix_add,
    ) {
        CliSelectRow(
            label = stringResource(R.string.cli_prof_edit_add_type),
            value = type,
            expanded = typeExpanded,
            onExpandToggle = { typeExpanded = !typeExpanded },
        )
        if (typeExpanded) {
            CLI_NEW_OUTBOUND_TYPES.forEach { option ->
                CliOptionRow(
                    text = option,
                    selected = option == type,
                    onSelect = {
                        typeExpanded = false
                        type = option
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliSheetActionsRow(
            onCancel = onDismiss,
            actions = listOf(
                CliSheetAction(
                    label = stringResource(R.string.cli_wizard_continue),
                    enabled = !busy,
                    onClick = { onContinue(type) },
                ),
            ),
        )
    }
}
