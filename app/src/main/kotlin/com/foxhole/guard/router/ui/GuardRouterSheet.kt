package com.foxhole.guard.router.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import com.foxhole.guard.router.RouterInstallCatalog
import com.foxhole.guard.router.RouterDiscoveryKind
import com.foxhole.guard.router.RouterDiscoveryIssue
import com.foxhole.guard.router.RouterDiscoveryResult
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.foxhole.guard.ui.cli.components.CliButton
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
                    CliButton(
                        label = stringResource(R.string.guard_router_vpn_router_pair),
                        onClick = { onConnect?.invoke(reset) ?: onPair() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !finding && (!reset || resetAvailable),
                        color = routerConnectColor(),
                        filled = false,
                    )
                }
                RouterDiscoveryKind.SSH_AVAILABLE -> CliButton(
                    label = stringResource(R.string.guard_router_vpn_router_install),
                    onClick = onSetup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !finding && setupEnabled,
                    filled = false,
                )
                else -> {
                    CliButton(
                        label = stringResource(if (notFound) R.string.guard_router_router_review_retry_search
                            else R.string.guard_router_router_review_find_router),
                        onClick = { onFind?.invoke() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !finding && connected && onFind != null,
                        filled = false,
                    )
                    CliButton(
                        label = stringResource(R.string.guard_router_router_review_enter_manually),
                        onClick = onPair,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !finding,
                        filled = false,
                    )
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
            if (discovery.kind == RouterDiscoveryKind.INSTALLED || discovery.kind == RouterDiscoveryKind.SSH_AVAILABLE) {
                Text(stringResource(R.string.guard_router_router_review_router_found), style = MaterialTheme.typography.titleMedium)
                Text(discovery.address, style = MaterialTheme.typography.bodySmall)
            }
            val discoveryMessage = stringResource(discovery.issue?.let { issue ->
                when (issue) {
                    RouterDiscoveryIssue.API_UNAVAILABLE -> if (discovery.kind == RouterDiscoveryKind.SSH_AVAILABLE)
                        R.string.guard_router_router_discovery_ssh_only else R.string.guard_router_router_discovery_api_absent
                    RouterDiscoveryIssue.TIMEOUT -> R.string.guard_router_router_discovery_timeout
                    RouterDiscoveryIssue.TLS -> R.string.guard_router_router_discovery_tls
                    RouterDiscoveryIssue.NETWORK -> R.string.guard_router_router_discovery_network
                    RouterDiscoveryIssue.PACKAGE_STATUS -> R.string.guard_router_router_discovery_package_check
                }
            } ?: when (discovery.kind) {
                RouterDiscoveryKind.INSTALLED -> R.string.guard_router_router_review_package_present
                RouterDiscoveryKind.SSH_AVAILABLE -> R.string.guard_router_router_review_setup_available
                RouterDiscoveryKind.UNKNOWN -> R.string.guard_router_router_review_package_unknown
                RouterDiscoveryKind.NOT_FOUND -> R.string.guard_router_router_review_not_found
            })
            if (!finding && discovery.kind == RouterDiscoveryKind.INSTALLED && discovery.issue == null) {
                val foundColor = routerConnectColor()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, foundColor),
                ) {
                    Text(discoveryMessage, Modifier.fillMaxWidth().padding(16.dp),
                        color = foundColor, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else Text(discoveryMessage, style = MaterialTheme.typography.bodyMedium)
            if (discovery.kind == RouterDiscoveryKind.UNKNOWN || discovery.kind == RouterDiscoveryKind.NOT_FOUND) {
                Text(discovery.address, style = MaterialTheme.typography.bodySmall)
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
