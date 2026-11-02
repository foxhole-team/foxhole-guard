package com.foxhole.guard.router.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.foxhole.guard.ui.cli.components.CliButton
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R

@Composable
internal fun GuardRouterProfileManagementContent(name: String, protocol: String, profile: String?,
    loading: Boolean, provisioned: Boolean, enabled: Boolean, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.lin_profiles), null, Modifier.size(20.dp))
            Text(protocol, style = MaterialTheme.typography.bodyMedium, color = LocalGuardRouterColors.current.muted)
        }
        if (profile != null) GuardRouterClientQr(profile, name)
        else Text(stringResource(if (loading) R.string.guard_router_router_control_profile_loading else R.string.guard_router_router_control_qr_unavailable),
            style = MaterialTheme.typography.bodySmall)
        CliButton(
            label = stringResource(R.string.guard_router_router_control_import_phone),
            onClick = onImport,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = enabled && profile != null,
            icon = R.drawable.lin_update,
            filled = false,
        )
        if (!provisioned) Text(stringResource(R.string.guard_router_router_control_client_template),
            style = MaterialTheme.typography.bodySmall, color = LocalGuardRouterColors.current.muted)
    }
}

@Composable
internal fun GuardRouterProfileActionsFooter(enabled: Boolean, onShare: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CliButton(
            label = stringResource(R.string.guard_router_router_review_share),
            onClick = onShare,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight(),
            enabled = enabled,
            icon = R.drawable.lin_export,
            filled = false,
        )
        CliButton(
            label = stringResource(R.string.guard_router_vpn_remove),
            onClick = onRemove,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight(),
            enabled = enabled,
            color = guardRouterDeleteColor(),
            icon = R.drawable.lin_trash,
            filled = false,
        )
    }
}
