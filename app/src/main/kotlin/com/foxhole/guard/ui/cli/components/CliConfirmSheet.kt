package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliLabelText

@Composable
internal fun CliConfirmSheet(
    title: String,
    question: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    confirmLabel: String? = null,
    note: String? = null,
    confirmTag: String? = null,
    cancelTag: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = title,
        icon = icon,
    ) {
        Text(
            text = cliLabelText(question),
            style = CliType.body,
            color = colors.fg,
        )
        note?.let {
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            Text(text = it, style = CliType.small, color = colors.warn)
        }
        content?.let {
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            Column { it() }
        }
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliSheetActionsRow(
            onCancel = onDismiss,
            cancelTag = cancelTag,
            actions = listOf(
                CliSheetAction(
                    label = confirmLabel ?: stringResource(R.string.cli_common_yes_confirm),
                    onClick = onConfirm,
                    testTag = confirmTag,
                ),
            ),
        )
    }
}

internal data class CliSheetAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val testTag: String? = null,
    val tone: CliSheetActionTone = CliSheetActionTone.CONFIRM,
)

internal enum class CliSheetActionTone { CONFIRM, DESTRUCTIVE, ACCENT }

@Composable
internal fun CliSheetActionsRow(
    onCancel: () -> Unit,
    actions: List<CliSheetAction>,
    modifier: Modifier = Modifier,
    cancelLabel: String = stringResource(R.string.cli_common_no_cancel),
    cancelTag: String? = null,
) {
    val colors = LocalCliColors.current
    val cancelButton: @Composable (Modifier) -> Unit = { buttonModifier ->
        CliButton(
            label = cancelLabel,
            color = colors.err,
            dashed = true,
            onClick = onCancel,
            modifier = buttonModifier.cliOptionalTestTag(cancelTag),
        )
    }
    val actionButton: @Composable (CliSheetAction, Modifier) -> Unit = { action, buttonModifier ->
        CliButton(
            label = action.label,
            filled = true,
            color = when (action.tone) {
                CliSheetActionTone.CONFIRM -> colors.ok
                CliSheetActionTone.DESTRUCTIVE -> colors.err
                CliSheetActionTone.ACCENT -> colors.accent
            },
            enabled = action.enabled,
            onClick = action.onClick,
            modifier = buttonModifier.cliOptionalTestTag(action.testTag),
        )
    }
    when {
        actions.isEmpty() -> cancelButton(modifier.fillMaxWidth())
        actions.size == 1 ->
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
            ) {
                cancelButton(Modifier.weight(1f))
                actionButton(actions.single(), Modifier.weight(1f))
            }
        else ->
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(CliSpacing.sm),
            ) {
                actions.forEach { action -> actionButton(action, Modifier.fillMaxWidth()) }
                cancelButton(Modifier.fillMaxWidth())
            }
    }
}

private fun Modifier.cliOptionalTestTag(tag: String?): Modifier =
    if (tag == null) this else testTag(tag)
