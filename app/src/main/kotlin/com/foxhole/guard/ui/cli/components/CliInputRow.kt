package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

@Composable
internal fun CliInputRow(
    prompt: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    trailingChipLabel: String? = null,
    onTrailingChip: (() -> Unit)? = null,
    autoFocus: Boolean = false,
    numeric: Boolean = false,
    password: Boolean = false,
    rowMinHeight: Dp = 48.dp,
) {
    val colors = LocalCliColors.current
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = rowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "$prompt > ", style = CliType.body, color = colors.accent)
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = CliType.body.copy(color = colors.fg),
                cursorBrush = SolidColor(colors.fg),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        numeric -> KeyboardType.Number
                        password -> KeyboardType.Password
                        else -> KeyboardType.Text
                    },
                    imeAction = ImeAction.Done,
                ),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            if (value.isEmpty()) {
                Text(text = "_", style = CliType.body, color = colors.faint)
            }
        }
        if (trailingChipLabel != null && onTrailingChip != null) {
            Spacer(modifier = Modifier.width(CliSpacing.sm))
            CliChip(label = trailingChipLabel, onClick = onTrailingChip)
        }
    }
}
