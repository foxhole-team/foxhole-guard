package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.Settings
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliDivider
import com.foxhole.guard.ui.cli.components.CliPanel

@Composable
internal fun CliExtrasSection(
    settings: Settings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onWebAppsEnabledChanged: (Boolean) -> Unit,
    onProxyServerEnabledChanged: (Boolean) -> Unit,
    onOpenWebApps: () -> Unit,
    onOpenProxyServer: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_extras),
        icon = R.drawable.pix_link,
        iconColor = colors.accent,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        CliModuleBlock(
            label = stringResource(R.string.cli_extras_webapps),
            icon = R.drawable.pix_webapps,
            checked = settings.webApps.enabled,
            onToggle = onWebAppsEnabledChanged,
            onOpenSettings = onOpenWebApps,
            settingsLabel = stringResource(R.string.cli_extras_open_settings),
        )
        CliDivider(color = colors.borderBright)
        CliModuleBlock(
            label = stringResource(R.string.cli_extras_proxy_server),
            icon = R.drawable.pix_device,
            checked = settings.expert.localSurfaces.allowLanAccess,
            onToggle = onProxyServerEnabledChanged,
            onOpenSettings = onOpenProxyServer,
            settingsLabel = stringResource(R.string.cli_extras_open_settings),
        )
    }
}
