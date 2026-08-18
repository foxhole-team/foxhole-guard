package com.foxhole.guard.ui.cli.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

@Composable
internal fun CliExternalLinkSheet(
    url: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val uriHandler = LocalUriHandler.current
    CliBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.cli_external_link_title),
        icon = R.drawable.pix_link,
    ) {
        Text(
            text = stringResource(R.string.cli_external_link_body),
            style = CliType.body,
            color = colors.dim,
        )
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        Text(
            text = url,
            style = CliType.body,
            color = colors.fg,
        )
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliSheetActionsRow(
            onCancel = onDismiss,
            actions = listOf(
                CliSheetAction(
                    label = stringResource(R.string.cli_common_yes_confirm),
                    onClick = {
                        onDismiss()
                        runCatching { uriHandler.openUri(url) }
                    },
                ),
            ),
        )
    }
}
