package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliModalCloseButton
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliTopBarHelpButton
import com.foxhole.guard.ui.cli.components.cliModalSurfaceColor

@Composable
internal fun CliManualProfileEditor(
    initialText: String,
    busy: Boolean,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = LocalCliColors.current
    var text by remember(initialText) { mutableStateOf(initialText) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cliModalSurfaceColor(LocalCliPanelAppearance.current, colors.panel))
            .padding(CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_prof_edit_manual_title),
            icon = R.drawable.lin_edit,
            titleColor = colors.fg,
            trailing = { CliTopBarHelpButton(bodyRes = R.string.cli_prof_edit_manual_hint) },
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = CliType.small.copy(color = colors.fg),
            cursorBrush = SolidColor(colors.fg),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(colors.panelAlt)
                .padding(CliSpacing.sm)
                .verticalScroll(rememberScrollState()),
        )
        CliEditorControlTypography {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = CliSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
            ) {
                CliModalCloseButton(
                    enabled = !busy,
                    dimWhenDisabled = false,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                CliButton(
                    label = stringResource(R.string.cli_prof_config_save_action),
                    color = colors.ok,
                    iconContent = { tint -> CliDisketteIcon(tint = tint) },
                    enabled = !busy && text.isNotBlank(),
                    dimWhenDisabled = false,
                    onClick = { onSave(text) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
