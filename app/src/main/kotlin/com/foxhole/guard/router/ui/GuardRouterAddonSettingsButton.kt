package com.foxhole.guard.router.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.components.CliRowDivider

@Composable
internal fun GuardRouterAddonSettingsButton() {
    val repository = (LocalContext.current.applicationContext as FoxholeApplication).appGraph.settingsRepository
    val shortcutVisible by repository.routerHomeShortcutVisible.collectAsStateWithLifecycle()
    val dockShortcutVisible by repository.routerDockShortcutVisible.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            painterResource(R.drawable.lin_settings),
            stringResource(R.string.guard_router_addon_settings_title),
            Modifier.size(22.dp),
            tint = LocalGuardRouterColors.current.muted,
        )
    }
    if (open) {
        CliBottomSheet(
            onDismiss = { open = false },
            title = stringResource(R.string.guard_router_addon_settings_title),
            icon = R.drawable.lin_settings,
            closeLabel = stringResource(R.string.cli_common_close_action),
        ) {
            CliPanel(modifier = Modifier.fillMaxWidth()) {
                CliToggleRow(
                    label = stringResource(R.string.guard_router_home_shortcut_label),
                    icon = R.drawable.lin_router,
                    checked = shortcutVisible,
                    onToggle = repository::updateRouterHomeShortcutVisible,
                )
                CliRowDivider()
                CliToggleRow(
                    label = stringResource(R.string.guard_router_dock_shortcut_label),
                    icon = R.drawable.lin_router,
                    checked = dockShortcutVisible,
                    onToggle = repository::updateRouterDockShortcutVisible,
                )
            }
        }
    }
}
