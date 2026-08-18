package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.cliLabelText

@Composable
@Suppress("LongParameterList")
internal fun CliInputModal(
    title: String,
    prompt: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    @DrawableRes icon: Int? = null,
    numeric: Boolean = false,
    belowInput: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = LocalCliColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(12.dp))
                .background(
                    cliModalSurfaceColor(LocalCliPanelAppearance.current, colors.panel),
                    RoundedCornerShape(12.dp),
                )
                .border(1.dp, colors.borderBright, RoundedCornerShape(12.dp))
                .padding(horizontal = CliSpacing.md, vertical = CliSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    CliPixIcon(
                        id = icon,
                        contentDescription = null,
                        size = 12.dp,
                        tint = if (icon == R.drawable.pix_trash) colors.err else colors.accent,
                    )
                    Spacer(modifier = Modifier.width(CliSpacing.xs))
                }
                Text(
                    text = cliLabelText(title),
                    style = CliType.small,
                    color = colors.dim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CliInputRow(
                prompt = prompt,
                value = value,
                onValueChange = onValueChange,
                onSubmit = onSubmit,
                autoFocus = true,
                numeric = numeric,
            )
            belowInput?.invoke(this)
        }
    }
}
