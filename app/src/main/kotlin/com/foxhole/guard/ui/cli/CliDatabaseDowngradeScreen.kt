package com.foxhole.guard.ui.cli

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.components.CliPanel

/** Fail-loud gate for an on-disk database created by a newer app version. */
@Composable
internal fun CliDatabaseDowngradeScreen(modifier: Modifier = Modifier) {
    val colors = LocalCliColors.current
    Column(
        modifier = modifier.fillMaxSize().background(colors.bg).padding(CliSpacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CliPanel(
            icon = R.drawable.pix_forbidden,
            title = stringResource(R.string.cli_downgrade_title)
        ) {
            Text(
                text = stringResource(R.string.database_downgrade_title),
                style = CliType.display,
                color = colors.err,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.database_downgrade_body),
                style = CliType.body,
                color = colors.fg,
                textAlign = TextAlign.Center,
            )
        }
    }
}
