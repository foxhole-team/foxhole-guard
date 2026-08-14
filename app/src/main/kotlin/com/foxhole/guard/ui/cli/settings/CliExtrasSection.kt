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

/**
 * Extras: components layered over the base VPN/Tor core. Each uses the same [CliModuleBlock] shape
 * as the modules — a toggle and an always-visible settings row — but keeps its own wording for that
 * row: an extra is not a module, and the label is the only place the difference shows.
 */
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
        // One rule between the extras, exactly as between the modules: the pair of rows above and
        // the pair below are two different things, and without a line they read as four rows of one.
        CliDivider(color = colors.borderBright)
        // Live again: the core grew a LAN proxy entry point across JNI, so this switch now arms a
        // real listener instead of a preference nothing read. It stays a request rather than a
        // state — whether the surface actually binds depends on the network, the credentials and
        // the carrier protocol, and its own screen reports what the core made of it.
        // The proxy server publishes the tunnel to the other devices on the wi-fi, so the pack's
        // "device" glyph is the honest one here.
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
