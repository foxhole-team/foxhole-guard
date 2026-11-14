package com.foxhole.guard.router.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    canInstallRouter: Boolean = false,
    canInspectRouter: Boolean = true,
    installationCatalog: RouterInstallCatalog? = null,
    onFind: (() -> Unit)? = null,
    discovery: RouterDiscoveryResult? = null,
    finding: Boolean = false,
    onConnect: ((Boolean) -> Unit)? = null,
    onCancelDiscovery: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    wifiConnected: Boolean? = null,
) {
    val observedWifi by rememberRouterWifiConnected()
    val connected = wifiConnected ?: observedWifi
    val notFound = discovery?.kind == RouterDiscoveryKind.NOT_FOUND
    GuardRouterSheetContent(
        when (discovery?.kind) {
            RouterDiscoveryKind.INSTALLED -> R.string.router_discovery_ready_to_pair
            RouterDiscoveryKind.SSH_AVAILABLE -> R.string.guard_router_router_review_router_found
            else -> R.string.guard_router_router_review_management
        },
        R.drawable.lin_router,
        titleMaxLines = 2,
        busy = finding,
        actions = { GuardRouterHelpButton(R.string.guard_router_vpn_router, R.string.guard_router_router_help_setup_pair, canInstallRouter = canInstallRouter, canInspectRouter = canInspectRouter) },
        footer = {
            when (discovery?.kind) {
                RouterDiscoveryKind.INSTALLED, RouterDiscoveryKind.SSH_AVAILABLE -> Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CliButton(label = stringResource(R.string.router_discovery_cancel),
                        onClick = { onCancelDiscovery?.invoke() }, enabled = !finding && onCancelDiscovery != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), filled = false)
                    if (discovery?.kind == RouterDiscoveryKind.INSTALLED) CliButton(
                        label = stringResource(R.string.router_discovery_continue),
                        onClick = { onConnect?.invoke(false) ?: onPair() },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = !finding,
                        color = routerConnectColor(), filled = false)
                    else CliButton(label = stringResource(R.string.router_discovery_continue), onClick = onSetup,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = !finding && setupEnabled, filled = false)
                }
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
            GuardRouterNavigationFooter(!finding, null, onClose)
        },
    ) {

        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        val illustrationHeight = if (windowHeight < 600.dp) 120.dp else 176.dp
        Box(Modifier.fillMaxWidth().height(illustrationHeight), contentAlignment = Alignment.Center) {
            GuardRouterIllustration(Modifier.fillMaxSize(), searching = finding,
                connected = discovery?.kind == RouterDiscoveryKind.INSTALLED)
        }
        RouterWifiIndicator(connected)
        if (discovery?.kind == RouterDiscoveryKind.INSTALLED || discovery?.kind == RouterDiscoveryKind.SSH_AVAILABLE) {

            if (discovery.kind == RouterDiscoveryKind.SSH_AVAILABLE) Text(
                stringResource(R.string.router_discovery_setup_question), style = MaterialTheme.typography.bodyMedium)
        } else {
            if (discovery == null) Text(stringResource(if (canInstallRouter) R.string.guard_router_vpn_router_intro else if (canInspectRouter) R.string.help_router_install_unavailable else R.string.help_router_install_manual), style = MaterialTheme.typography.bodyMedium)
            else {
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
                    RouterDiscoveryKind.SSH_AVAILABLE -> if (canInstallRouter) R.string.guard_router_router_review_setup_available else if (canInspectRouter) R.string.help_router_install_unavailable else R.string.help_router_install_manual
                    RouterDiscoveryKind.UNKNOWN -> R.string.guard_router_router_review_package_unknown
                    RouterDiscoveryKind.NOT_FOUND -> R.string.guard_router_router_review_not_found
                })
                Text(discoveryMessage, style = MaterialTheme.typography.bodyMedium)
                if (discovery.kind == RouterDiscoveryKind.UNKNOWN || discovery.kind == RouterDiscoveryKind.NOT_FOUND) {

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
}
