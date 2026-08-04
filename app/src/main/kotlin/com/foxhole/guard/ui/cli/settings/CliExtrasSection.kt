package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.Settings
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliDivider
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliToggleRow

/**
 * Extras: components layered over the base VPN/Tor core. Each uses the same block style — a
 * toggle, a pixel rule beneath it and an always-visible settings row. LAN proxy, private file
 * sharing and web apps. I2P lives in the section
 * tor / i2p.
 */
@Composable
internal fun CliExtrasSection(
    settings: Settings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenFileShare: () -> Unit,
    onWebAppsEnabledChanged: (Boolean) -> Unit,
    onOpenWebApps: () -> Unit,
    onOpenProxyServer: () -> Unit,
) {
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_extras),
        icon = R.drawable.pix_link,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        CliActionRow(
            label = stringResource(R.string.cli_extras_file_share),
            value = stringResource(R.string.cli_extras_file_share_note),
            onTap = onOpenFileShare,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliExtrasComponentBlock(
            label = stringResource(R.string.cli_extras_webapps),
            checked = settings.webApps.enabled,
            onToggle = onWebAppsEnabledChanged,
            onOpenSettings = onOpenWebApps,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        // Shown but not operable: FoxCore has no inbound listener at all (its config carries
        // outbounds only, and the translator accepts a single non-tun inbound — the loopback
        // control proxy). The row states that rather than offering a switch that changes nothing;
        // its settings screen stays reachable so the ports and credentials can be prepared.
        CliExtrasComponentBlock(
            label = stringResource(R.string.cli_extras_proxy_server),
            checked = false,
            onToggle = {},
            toggleEnabled = false,
            note = stringResource(R.string.cli_extras_in_development),
            onOpenSettings = onOpenProxyServer,
        )
    }
}

/** One extras component block: toggle, pixel rule, settings row. */
@Composable
private fun CliExtrasComponentBlock(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    note: String? = null,
    toggleEnabled: Boolean = true,
) {
    CliToggleRow(
        label = label,
        checked = checked,
        onToggle = onToggle,
        note = note,
        enabled = toggleEnabled,
    )
    CliDivider()
    CliActionRow(
        label = stringResource(R.string.cli_extras_open_settings),
        onTap = onOpenSettings,
    )
}
