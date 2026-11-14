package com.foxhole.guard.router.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliHelpNote
import com.foxhole.guard.ui.cli.help.GuardHelpBlocks
import com.foxhole.guard.ui.cli.help.guardRouterContextBlocks

@Composable
internal fun GuardRouterHelpButton(@StringRes titleRes: Int, @StringRes helpRes: Int,
    canInstallRouter: Boolean = false, canInspectRouter: Boolean = true, @StringRes actionLabelRes: Int? = null,
    helpContent: (@Composable () -> Unit)? = null) {
    var expanded by rememberSaveable(titleRes, helpRes) { mutableStateOf(false) }
    val description = stringResource(R.string.guard_router_router_help_open, stringResource(titleRes))
    if (actionLabelRes != null) TextButton(onClick = { expanded = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(painterResource(R.drawable.lin_help), null, Modifier.size(28.dp), tint = LocalGuardRouterColors.current.muted)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(actionLabelRes), maxLines = 2)
    } else IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.lin_help), description, Modifier.size(28.dp), tint = LocalGuardRouterColors.current.muted)
    }
    if (expanded) CliBottomSheet(onDismiss = { expanded = false },
        title = stringResource(if (helpRes == R.string.guard_router_router_help_setup_pair) R.string.help_router_title else titleRes),
        icon = R.drawable.lin_help, closeLabel = stringResource(R.string.cli_common_close_action)) {
        val blocks = guardRouterContextBlocks(helpRes, canInstallRouter, canInspectRouter)
        if (helpContent != null) helpContent()
        else if (blocks != null) GuardHelpBlocks(blocks, scope = "router-$helpRes")
        else CliHelpNote(text = stringResource(helpRes))
    }
}
