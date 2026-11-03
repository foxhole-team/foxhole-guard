package com.foxhole.guard.router.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliHelpNote

@Composable
internal fun GuardRouterHelpButton(@StringRes titleRes: Int, @StringRes helpRes: Int) {
    var expanded by remember(titleRes, helpRes) { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(painterResource(R.drawable.lin_help),
            stringResource(R.string.guard_router_router_help_open, stringResource(titleRes)),
            Modifier.size(22.dp), tint = LocalGuardRouterColors.current.muted)
    }
    if (expanded) CliBottomSheet(
        onDismiss = { expanded = false },
        title = stringResource(titleRes), icon = R.drawable.lin_help,
        closeLabel = stringResource(R.string.cli_common_close_action),
    ) {
        CliHelpNote(text = stringResource(helpRes))
    }
}
