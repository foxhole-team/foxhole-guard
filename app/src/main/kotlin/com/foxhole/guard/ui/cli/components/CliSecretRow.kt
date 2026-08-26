package com.foxhole.guard.ui.cli.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliIconSize
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliRowTextStyle

@Composable
internal fun CliSecretRow(
    prompt: String,
    value: String,
    onValueChange: (String) -> Unit,
    clipboardLabel: String,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    onCopied: (() -> Unit)? = null,
    rowMinHeight: Dp = 48.dp,
) {
    val colors = LocalCliColors.current
    val context = LocalContext.current
    var editing by rememberSaveable(prompt) { mutableStateOf(false) }
    var editDraft by remember(prompt) { mutableStateOf("") }
    var editDraftChanged by remember(prompt) { mutableStateOf(false) }
    var revealed by rememberSaveable(prompt, value.isBlank()) { mutableStateOf(false) }
    val hasValue = value.isNotBlank()
    val actionSize = minOf(rowMinHeight, SECRET_ACTION_SIZE)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = rowMinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = prompt,
                style = cliRowTextStyle(),
                color = colors.dim,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(CliSpacing.sm))
            Text(
                text = cliSecretDisplay(value = value, revealed = revealed),
                style = cliRowTextStyle(),
                color = if (revealed) colors.fg else colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CliSecretAction(
                icon = if (revealed) R.drawable.lin_eye_off else R.drawable.lin_eye,
                description = stringResource(
                    if (revealed) R.string.cli_secret_action_hide else R.string.cli_secret_action_show,
                ),
                tint = if (hasValue) {
                    if (revealed) colors.accent else colors.dim
                } else {
                    colors.faint
                },
                enabled = hasValue,
                size = actionSize,
                onClick = { revealed = !revealed },
            )
            CliSecretAction(
                icon = R.drawable.lin_copy,
                description = stringResource(R.string.cli_lan_proxy_copy_pass),
                tint = if (hasValue) colors.dim else colors.faint,
                enabled = hasValue,
                size = actionSize,
                onClick = {
                    if (onCopy != null) {
                        onCopy()
                    } else {
                        cliCopySecretToClipboard(context, clipboardLabel, value)
                    }
                    onCopied?.invoke()
                },
            )
            CliSecretAction(
                icon = R.drawable.lin_edit,
                description = stringResource(R.string.cli_lock_change),
                tint = if (editing) colors.accent else colors.dim,
                size = actionSize,
                onClick = {
                    editing = !editing
                    editDraft = ""
                    editDraftChanged = false
                },
            )
        }
        if (editing) {
            CliInputRow(
                prompt = prompt,
                value = editDraft,
                onValueChange = { nextDraft ->
                    val change = cliSecretDraftChange(nextDraft, editDraftChanged)
                    editDraft = change.draft
                    editDraftChanged = change.changed
                    change.replacement?.let(onValueChange)
                },
                onSubmit = {
                    editing = false
                    editDraft = ""
                    editDraftChanged = false
                },
                autoFocus = true,
                password = !revealed,
                rowMinHeight = rowMinHeight,
            )
        }
    }
}

internal fun cliSecretDisplay(value: String, revealed: Boolean): String = when {
    value.isBlank() -> ""
    revealed -> value
    else -> SECRET_MASK
}

internal data class CliSecretDraftChange(
    val draft: String,
    val changed: Boolean,
    val replacement: String?,
)

internal fun cliSecretDraftChange(
    nextDraft: String,
    previouslyChanged: Boolean,
): CliSecretDraftChange {
    val changed = previouslyChanged || nextDraft.isNotEmpty()
    return CliSecretDraftChange(
        draft = nextDraft,
        changed = changed,
        replacement = if (changed) nextDraft else null,
    )
}

@Composable
private fun CliSecretAction(
    @DrawableRes icon: Int,
    description: String,
    tint: Color,
    enabled: Boolean = true,
    size: Dp = SECRET_ACTION_SIZE,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            }
            .cliPressable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CliIcon(
            id = icon,
            contentDescription = null,
            size = CliIconSize.row,
            tint = tint,
        )
    }
}

internal fun cliCopySecretToClipboard(
    context: Context,
    label: String,
    secret: String,
) {
    if (secret.isBlank()) {
        return
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip =
        ClipData.newPlainText(label, secret).apply {
            description.extras =
                PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
        }
    clipboard.setPrimaryClip(clip)
}

private const val SECRET_MASK = "••••••••"
private val SECRET_ACTION_SIZE = 48.dp
