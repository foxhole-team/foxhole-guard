package com.foxhole.guard.router.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import com.foxhole.guard.ui.cli.CliRadius
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterControlState
import com.foxhole.guard.router.routerStatusPresentation
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.cliFlagIconSize

@Composable
internal fun GuardRouterStatusCard(state: RouterControlState, onProfileClick: (() -> Unit)? = null, onLocalTrafficClick: (() -> Unit)? = null) {
    val status = routerStatusPresentation(state)
    val style = MaterialTheme.typography.bodyMedium
    val colors = LocalGuardRouterColors.current
    val rowHeight = with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() + 8.dp }.coerceAtLeast(40.dp)
    val profileChevronHeight = cliFlagIconSize(style, 1, 1).height
    val connectionColor = when (status.connected) {
        true -> routerConnectColor()
        false -> MaterialTheme.colorScheme.error
        null -> colors.muted
    }
    val localTraffic = com.foxhole.guard.router.routerCountryDirectControl(state.preferences, state.routerCountryCode, state.snapshot)
    val unknown = stringResource(R.string.guard_router_router_review_status_unknown)
    val rowShape = RoundedCornerShape(CliRadius.control)
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)
        .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.router_status_title), Modifier.fillMaxWidth().semantics { heading() },
            textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Icon(painterResource(R.drawable.lin_power), null, Modifier.size(20.dp), tint = connectionColor)
            Text(when (status.connected) {
                true -> stringResource(R.string.router_status_connected)
                false -> stringResource(R.string.router_status_disconnected)
                null -> unknown
            }, style = style, color = connectionColor, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = rowHeight.coerceAtLeast(48.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), rowShape)
            .clip(rowShape)
            .clickable(enabled = onProfileClick != null && state.controlsAvailable, onClick = { onProfileClick?.invoke() })
            .padding(horizontal = 8.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.lin_profiles), null, Modifier.size(20.dp), tint = colors.muted)
            Text(stringResource(R.string.router_status_profile), style = style, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(status.profileName ?: if (status.connected == null) unknown else stringResource(R.string.router_status_no_profile),
                Modifier.weight(1f), textAlign = TextAlign.End, style = style, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (onProfileClick != null) Icon(painterResource(R.drawable.router_status_chevron), null,
                Modifier.size(width = profileChevronHeight * (10f / 18f), height = profileChevronHeight), tint = colors.muted)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = rowHeight.coerceAtLeast(48.dp))
                .clip(rowShape)
                .clickable(enabled = onLocalTrafficClick != null && state.controlsAvailable && localTraffic.canChange, onClick = { onLocalTrafficClick?.invoke() }).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), rowShape).padding(horizontal = 8.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.lin_globe), null, Modifier.size(20.dp), tint = colors.muted)
            Text(stringResource(R.string.router_status_location), Modifier.weight(1f), style = style, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (status.countryCode != null) {
                CliFlagIcon(countryCode = status.countryCode, style = style)
                Text(status.countryCode, style = style)
            }
            else Text(if (status.localTraffic == false) stringResource(R.string.router_status_local_off) else unknown,
                Modifier.weight(1f), textAlign = TextAlign.End, style = style, color = colors.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
