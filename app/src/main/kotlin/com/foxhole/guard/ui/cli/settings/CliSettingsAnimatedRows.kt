package com.foxhole.guard.ui.cli.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.foxhole.guard.ui.cli.cliVerticalEnter
import com.foxhole.guard.ui.cli.cliVerticalExit

@Composable
internal fun CliSettingsAnimatedRows(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = cliVerticalEnter(),
        exit = cliVerticalExit(),
        modifier = modifier,
    ) {
        Column(content = content)
    }
}
