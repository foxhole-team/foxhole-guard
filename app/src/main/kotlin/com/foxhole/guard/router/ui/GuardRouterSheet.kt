package com.foxhole.guard.router.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.Alignment
import com.foxhole.guard.router.RouterInstallCatalog
import com.foxhole.guard.router.RouterDiscoveryKind
import com.foxhole.guard.router.RouterDiscoveryResult
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R

@Composable
internal fun GuardRouterEntrySheet(
    onSetup: () -> Unit,
    onPair: () -> Unit,
    setupEnabled: Boolean = true,
    installationCatalog: RouterInstallCatalog? = null,
    onFind: (() -> Unit)? = null,
    discovery: RouterDiscoveryResult? = null,
    finding: Boolean = false,
    onConnect: ((Boolean) -> Unit)? = null,
    wifiConnected: Boolean? = null,
) {
    val observedWifi by rememberRouterWifiConnected()
    val connected = wifiConnected ?: observedWifi
    val notFound = discovery?.kind == RouterDiscoveryKind.NOT_FOUND
    var reset by remember(discovery?.discoveryId) { mutableStateOf(false) }
    val resetAvailable = !finding && discovery?.resetSupported == true && discovery.pinConfigured != false && onConnect != null
    GuardRouterSheetContent(
        R.string.guard_router_router_review_management,
        R.drawable.lin_router,
        busy = finding,
        actions = { GuardRouterHelpButton(R.string.guard_router_vpn_router, R.string.guard_router_router_help_setup_pair) },
        footer = {
            when (discovery?.kind) {
                RouterDiscoveryKind.INSTALLED -> {
                    OutlinedButton(onClick = { onConnect?.invoke(reset) ?: onPair() },
                        enabled = !finding && (!reset || resetAvailable),
                        shape = MaterialTheme.shapes.extraSmall,
                        border = BorderStroke(1.5.dp, routerConnectColor()),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = routerConnectColor()),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                        Text(stringResource(R.string.guard_router_vpn_router_pair))
                    }
                }
                RouterDiscoveryKind.SSH_AVAILABLE -> OutlinedButton(onClick = onSetup,
                    enabled = !finding && setupEnabled, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                    Text(stringResource(R.string.guard_router_vpn_router_install))
                }
                else -> {
                    OutlinedButton(onClick = { onFind?.invoke() }, enabled = !finding && connected && onFind != null, shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                        Text(stringResource(if (notFound) R.string.guard_router_router_review_retry_search
                            else R.string.guard_router_router_review_find_router))
                    }
                    if (notFound) TextButton(onClick = onPair, enabled = !finding, shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.guard_router_router_review_enter_manually))
                    }
                }
            }
        },
    ) {
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        val illustrationHeight = if (windowHeight < 600.dp) 96.dp else 192.dp
        Box(Modifier.fillMaxWidth().height(illustrationHeight), contentAlignment = Alignment.Center) {
            GuardRouterIllustration(Modifier.fillMaxSize())
        }
        RouterWifiIndicator(connected)
        if (discovery == null) Text(stringResource(R.string.guard_router_vpn_router_intro), style = MaterialTheme.typography.bodyMedium)
        else {
            if (discovery.kind != RouterDiscoveryKind.NOT_FOUND) {
                Text(stringResource(R.string.guard_router_router_review_router_found), style = MaterialTheme.typography.titleMedium)
                Text(discovery.address, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(when (discovery.kind) {
                RouterDiscoveryKind.INSTALLED -> R.string.guard_router_router_review_package_present
                RouterDiscoveryKind.SSH_AVAILABLE -> R.string.guard_router_router_review_setup_available
                RouterDiscoveryKind.UNKNOWN -> R.string.guard_router_router_review_package_unknown
                RouterDiscoveryKind.NOT_FOUND -> R.string.guard_router_router_review_not_found
            }), style = MaterialTheme.typography.bodyMedium)
            if (discovery.kind == RouterDiscoveryKind.UNKNOWN) OutlinedButton(onClick = onPair,
                enabled = !finding, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.guard_router_router_review_manual_address))
            }
            if (discovery.kind == RouterDiscoveryKind.INSTALLED) {
                if (discovery.pinConfigured == false) Text(stringResource(R.string.guard_router_router_review_owner_setup_required),
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(reset, onCheckedChange = { checked -> if (!checked || resetAvailable) reset = checked },
                        enabled = !finding && (reset || resetAvailable))
                    Text(stringResource(R.string.guard_router_router_review_reset_before_connect), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    GuardRouterHelpButton(R.string.guard_router_router_review_reset_before_connect, R.string.guard_router_router_help_reset_foxhole)
                }
                if (!resetAvailable) Text(stringResource(R.string.guard_router_router_review_reset_unavailable),
                    style = MaterialTheme.typography.bodySmall, color = LocalGuardRouterColors.current.muted)
            }
        }
        Text(stringResource(R.string.guard_router_vpn_router_local_network_purpose), style = MaterialTheme.typography.bodySmall,
            color = LocalGuardRouterColors.current.muted)
        if (!notFound) {
            Text(stringResource(R.string.guard_router_router_review_compatibility), style = MaterialTheme.typography.titleMedium)
            val versions = (installationCatalog?.variants.orEmpty().flatMap { variant ->
                variant.supportedOpenWrt.map { it to variant.supportedTargets }
            } + installationCatalog?.compactPlatforms.orEmpty().flatMap { platform ->
                platform.supportedOpenWrt.map { it to platform.supportedTargets }
            }).groupBy({ it.first }, { it.second }).toSortedMap()
            if (versions.isEmpty()) {
                Text(stringResource(R.string.guard_router_router_review_supported_platforms), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.guard_router_router_review_catalog_pending), style = MaterialTheme.typography.bodySmall)
            }
            else GuardRouterReviewTable(versions.map { (version, targets) -> "OpenWrt $version" to targets.flatten().distinct().sorted().joinToString(" · ") })
        }
    }
}
