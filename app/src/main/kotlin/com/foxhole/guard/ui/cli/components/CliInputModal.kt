package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

/**
 * The narrow custom-value input modal, shared by every settings section: a title line above the
 * terminal field [CliInputRow]. [numeric] raises the digit keyboard for hours, ports and mtu;
 * otherwise the alphabetic one for domains and addresses. Enter confirms; a tap outside or system
 * back closes without applying.
 */
@Composable
internal fun CliInputModal(
    title: String,
    prompt: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    numeric: Boolean = false,
) {
    val colors = LocalCliColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .border(1.dp, colors.borderBright)
                .padding(horizontal = CliSpacing.md, vertical = CliSpacing.sm),
        ) {
            Text(text = title, style = CliType.small, color = colors.dim)
            CliInputRow(
                prompt = prompt,
                value = value,
                onValueChange = onValueChange,
                onSubmit = onSubmit,
                autoFocus = true,
                numeric = numeric,
            )
        }
    }
}
