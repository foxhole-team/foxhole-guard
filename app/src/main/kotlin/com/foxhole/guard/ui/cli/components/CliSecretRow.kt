package com.foxhole.guard.ui.cli.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.text.style.TextOverflow
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
    onCopied: (() -> Unit)? = null,
) {
    val colors = LocalCliColors.current
    val context = LocalContext.current
    var editing by rememberSaveable(prompt) { mutableStateOf(false) }
    val revealSource = remember { MutableInteractionSource() }
    val revealed by revealSource.collectIsPressedAsState()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
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
                icon = R.drawable.pix_edit,
                description = stringResource(R.string.cli_secret_action_edit),
                tint = if (editing) colors.accent else colors.dim,
                onClick = { editing = !editing },
            )
            Box(
                modifier = Modifier
                    .size(SECRET_ACTION_SIZE)
                    .clickable(
                        interactionSource = revealSource,
                        indication = null,
                        onClickLabel = stringResource(R.string.cli_secret_action_reveal),
                    ) {},
                contentAlignment = Alignment.Center,
            ) {
                CliPixIcon(
                    id = R.drawable.pix_incognito,
                    contentDescription = stringResource(R.string.cli_secret_action_reveal),
                    size = CliIconSize.row,
                    tint = if (revealed) colors.accent else colors.dim,
                )
            }
            CliSecretAction(
                icon = R.drawable.pix_copy,
                description = stringResource(R.string.cli_secret_action_copy),
                tint = colors.dim,
                onClick = {
                    cliCopySecretToClipboard(context, clipboardLabel, value)
                    onCopied?.invoke()
                },
            )
        }
        if (editing) {
            CliInputRow(
                prompt = prompt,
                value = value,
                onValueChange = onValueChange,
                autoFocus = true,
                password = !revealed,
            )
        }
    }
}

private fun cliSecretDisplay(value: String, revealed: Boolean): String = when {
    value.isEmpty() -> ""
    revealed -> value
    else -> SECRET_MASK
}

@Composable
private fun CliSecretAction(
    @DrawableRes icon: Int,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(SECRET_ACTION_SIZE).cliPressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CliPixIcon(
            id = icon,
            contentDescription = description,
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
    if (secret.isEmpty()) {
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
private val SECRET_ACTION_SIZE = 40.dp
