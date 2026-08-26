package com.foxhole.guard.ui.cli.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.window.DialogProperties
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliRadius
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.LocalCliPixelArtEnabled
import com.foxhole.guard.ui.cli.cliHeadingOpticalOffsetFor
import com.foxhole.guard.ui.cli.cliHeadingText
import com.foxhole.guard.ui.cli.cliTitleStyle

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
    val pixelArtEnabled = LocalCliPixelArtEnabled.current
    val shownTitle = cliHeadingText(title)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = true),
    ) {
        val shape = RoundedCornerShape(CliRadius.modal)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, shape)
                .background(
                    cliModalSurfaceColor(LocalCliPanelAppearance.current, colors.panel),
                    shape,
                )
                .border(1.dp, colors.borderBright, shape)
                .padding(horizontal = CliSpacing.md, vertical = CliSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                if (icon != null) {
                    val iconRole = cliModalHeaderIconRoleFor(CliSheetHeaderIconRole.DEFAULT, icon)
                    val headerIconSize = cliModalHeaderIconSizeFor(iconRole)
                    Box(
                        modifier = Modifier
                            .width(headerIconSize)
                            .height(CliHeaderControlSlotHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        CliIcon(
                            id = icon,
                            contentDescription = null,
                            size = headerIconSize,
                            tint = when {
                                icon == R.drawable.lin_trash -> colors.err
                                iconRole == CliSheetHeaderIconRole.INFORMATION -> colors.info
                                else -> colors.accent
                            },
                            modifier = Modifier.offset(
                                y = cliModalHeaderIconOffsetFor(
                                    shownTitle,
                                    iconRole,
                                    pixelArtEnabled,
                                ),
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.width(CliSpacing.xs))
                }
                Text(
                    text = shownTitle,
                    style = cliTitleStyle(shownTitle),
                    color = colors.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(
                        y = cliHeadingOpticalOffsetFor(shownTitle, pixelArtEnabled),
                    ),
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
            Spacer(modifier = Modifier.height(CliSpacing.md))
            CliModalCloseButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
