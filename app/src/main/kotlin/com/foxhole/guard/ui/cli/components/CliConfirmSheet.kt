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

/**
 * The one yes/no modal of the app. Every consequential or destructive confirmation — the firewall
 * switch, the encryption switch, erasing local data, dropping a protocol — rises from the bottom
 * edge through this sheet instead of unfolding inline under the row that asked.
 *
 * Why one shape: an inline y/n grows the screen under the user's finger, so the "yes" chip lands
 * where the row used to be and the question can scroll out of sight while it is armed. A sheet
 * cannot be missed, a swipe or the scrim always means no, and the answer is given in one fixed
 * place whatever asked it.
 *
 * The button row is [CliSheetActionsRow] — the app-wide sheet palette: dashed err cancel at
 * the left, filled ok confirm at the right, both spanning the sheet. This row is deliberately
 * full width and is NOT subject to the trailing-edge rule that governs inline controls.
 *
 * [content] carries anything the answer depends on (the encryption consent's companion switches);
 * it is laid out between the question and the buttons, inside the sheet's own gutters.
 */
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
    // Device tests drive the two answers by tag; a caller that has such a test names them here.
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
            text = question,
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

/** One confirm/save/enable action of a sheet's bottom row. */
internal data class CliSheetAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val testTag: String? = null,
    val tone: CliSheetActionTone = CliSheetActionTone.CONFIRM,
)

internal enum class CliSheetActionTone { CONFIRM, DESTRUCTIVE, ACCENT }

/**
 * The single bottom action row of every sheet: cancel at the left in a dashed err outline,
 * the confirming action filled in the ok tone at the right, both spanning the sheet. More
 * than one action stacks full-width above a full-width cancel — three buttons across cannot
 * hold their labels on narrow devices. No action at all leaves cancel alone, full width.
 */
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
