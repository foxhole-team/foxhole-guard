package com.foxhole.guard.router.ui

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import com.foxhole.guard.ui.cli.CliRadius
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.router.*
import com.foxhole.guard.ui.cli.help.HelpBlock
import com.foxhole.guard.ui.cli.help.GuardHelpBlocks
import com.foxhole.guard.ui.cli.components.CliButton

@Composable
internal fun RouterVpnCombinedHelp() {
    GuardHelpBlocks(listOf(
        HelpBlock.Heading(R.string.guard_router_router_review_profiles, R.drawable.lin_profiles),
        HelpBlock.Paragraph(R.string.help_router_transfer),
        HelpBlock.Paragraph(R.string.guard_router_router_control_vpn_help),
        HelpBlock.Heading(R.string.guard_router_router_control_lan_clients, R.drawable.lin_device),
        HelpBlock.Paragraph(R.string.guard_router_router_control_lan_help),
    ), scope = "router-vpn-and-devices")
}

@Composable
internal fun RouterSettingsCombinedHelp() {
    GuardHelpBlocks(listOf(
        HelpBlock.Heading(R.string.guard_router_router_control_enable, R.drawable.lin_settings), HelpBlock.Paragraph(R.string.guard_router_router_control_enable_help),
        HelpBlock.Heading(R.string.router_home_server, R.drawable.lin_router), HelpBlock.Paragraph(R.string.router_home_server_help),
        HelpBlock.Heading(R.string.guard_router_router_review_local_traffic, R.drawable.lin_globe), HelpBlock.Paragraph(R.string.guard_router_router_help_country_source),
        HelpBlock.Heading(R.string.router_smart_title, R.drawable.lin_router), HelpBlock.Paragraph(R.string.guard_router_router_control_home_help),
        HelpBlock.Heading(R.string.guard_router_router_control_home_vpn, R.drawable.lin_router), HelpBlock.Paragraph(R.string.guard_router_router_control_home_vpn_help),
        HelpBlock.Heading(R.string.guard_router_router_control_hide_offline, R.drawable.lin_eye_off), HelpBlock.Paragraph(R.string.guard_router_router_control_hide_offline_help),
    ), scope = "router-settings")
}

@Composable
internal fun RouterAboutContent(state: RouterControlState) {
    val unknown = stringResource(R.string.router_about_unknown)
    val about = state.about?.takeIf { state.aboutPairingId == state.selectedRouterId }
    val countryCode = com.foxhole.guard.router.routerDisplayCountryCode(state.preferences, state.routerCountryCode,
        state.snapshot?.countries).orEmpty()
    val country = countryCode.ifBlank { unknown }
    val localTraffic = routerCountryDirectControl(state.preferences, state.routerCountryCode, state.snapshot).checked
    val serverEnabled = routerVpnServerEnabled(state.snapshot)
    val rows = listOf(
        stringResource(R.string.router_about_package) to (about?.packageVersion ?: state.lastAuthenticatedPackageVersion ?: unknown),
        stringResource(R.string.router_about_firmware) to (about?.routerInfo?.firmwareVersion ?: unknown),
        stringResource(R.string.router_about_model) to (about?.routerInfo?.model ?: unknown),
        stringResource(R.string.router_about_country) to country,
        stringResource(R.string.router_about_storage) to (about?.storage ?: unknown),
        stringResource(R.string.router_about_ip) to (about?.routerInfo?.lanAddress ?: unknown),
    ) + (if (localTraffic) listOf(stringResource(R.string.router_about_local_traffic) to
        stringResource(R.string.router_setting_enabled)) else emptyList()) + listOf(
        stringResource(R.string.router_home_server) to stringResource(when (serverEnabled) {
            true -> R.string.router_setting_enabled
            false -> R.string.router_setting_disabled
            null -> R.string.router_about_unknown
        }),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    GuardRouterReviewTable(rows, listOf(R.drawable.lin_journal, R.drawable.lin_journal, R.drawable.lin_router,
        R.drawable.lin_globe, R.drawable.lin_settings, R.drawable.lin_globe) +
        (if (localTraffic) listOf(R.drawable.lin_globe) else emptyList()) + listOf(R.drawable.lin_router),
        countryCodes = countryCode.takeIf { it.matches(Regex("[A-Z]{2}")) }?.let { mapOf(3 to it) }.orEmpty(), filled = false)
    }
}

@Composable
internal fun RouterServerActionContent(state: RouterControlState, publicIpAck: Boolean, onPublicIpAck: (Boolean) -> Unit,
    onReadStatus: () -> Unit) {
    val plan = state.vpnServer.plan
    val job = state.vpnServer.job
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val info = MaterialTheme.colorScheme.secondary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (job != null) {
            if (job.running) {
                LinearProgressIndicator(progress = { job.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.router_server_job_running) + " " + job.progressPercent + "%")
                if (state.stage != RouterControlStage.CONNECTED) Text(stringResource(R.string.router_server_status_unknown),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text(stringResource(if (job.succeeded) R.string.router_server_job_done else R.string.router_server_job_failed),
                color = if (job.succeeded) routerConnectColor() else MaterialTheme.colorScheme.error)
            RouterManagedActionButton(stringResource(R.string.router_server_read_status), onReadStatus,
                Modifier.fillMaxWidth(), enabled = state.stage == RouterControlStage.CONNECTED && !state.busy)
        } else {
            Text(stringResource(when {
                plan?.available != true -> R.string.router_server_unavailable
                plan.action == "reuse" -> R.string.router_server_reuse
                else -> R.string.router_server_install
            }, plan?.componentVersion.orEmpty()), style = MaterialTheme.typography.bodyMedium)
            if (plan?.downloadBytes != null) {
                Text(stringResource(R.string.router_server_download,
                    Formatter.formatFileSize(context, plan.downloadBytes)), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { uri.openUri("https://github.com/foxhole-team/foxhole-openwrt-client/releases") }) {
                Text("GitHub", color = info)
            }
            Surface(color = info.copy(alpha = .08f), border = BorderStroke(1.dp, info.copy(alpha = .65f)),
                shape = MaterialTheme.shapes.small) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(painterResource(R.drawable.lin_info), null, Modifier.size(20.dp), tint = info)
                    Text(stringResource(R.string.router_server_public_ip), Modifier.weight(1f),
                        color = info, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (plan?.available == true) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = publicIpAck, onCheckedChange = onPublicIpAck)
                Text(stringResource(R.string.router_server_public_ack), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun RouterServerActionFooter(state: RouterControlState, publicIpAck: Boolean, onConfirm: () -> Unit,
    onBack: () -> Unit, onClose: (() -> Unit)?) {
    if (state.vpnServer.job != null) {
        GuardRouterNavigationFooter(!state.busy, onBack, onClose)
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RouterManagedActionButton(stringResource(R.string.guard_router_router_review_back), onBack, Modifier.weight(1f), enabled = !state.busy)
        RouterManagedActionButton(stringResource(R.string.router_server_enable), onConfirm, Modifier.weight(1f),
            enabled = !state.busy && state.vpnServer.plan?.available == true && publicIpAck, positive = true)
    }
}

@Composable
private fun RouterManagedActionButton(label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true, positive: Boolean = false) {
    CliButton(label = label, onClick = onClick, modifier = modifier.heightIn(min = GuardRouterActionHeight), enabled = enabled,
        color = if (positive) routerConnectColor() else LocalGuardRouterColors.current.foreground, filled = false)
}


@Composable
internal fun RouterConfirmationModal(title: Int, confirmLabel: Int, enabled: Boolean, busy: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit, icon: Int = R.drawable.lin_trash, destructive: Boolean = true, titleMaxLines: Int = 2,
    content: @Composable ColumnScope.() -> Unit) {
    com.foxhole.guard.ui.cli.components.CliBottomSheet(onDismiss = onDismiss,
        title = stringResource(title), titleMaxLines = titleMaxLines, icon = icon, sheetGesturesEnabled = !busy, closeEnabled = !busy,
        closeLabel = stringResource(R.string.guard_router_vpn_cancel),
        footerTrailing = {
            if (confirmLabel == R.string.router_switch_profile) RouterPrimaryActionButton(
                stringResource(confirmLabel), onConfirm, Modifier.weight(1f), enabled = enabled && !busy)
            else CliButton(label = stringResource(confirmLabel), onClick = onConfirm, enabled = enabled && !busy,
                color = if (destructive) guardRouterDeleteColor() else routerConnectColor(), filled = false, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
        }, content = content)
}

@Composable
internal fun RouterVpnProfileChoiceContent(servers: List<kotlinx.serialization.json.JsonObject>, chosen: String?,
    enabled: Boolean, onChoose: (String) -> Unit) {
    if (servers.isEmpty()) Text(stringResource(R.string.guard_router_router_review_no_profiles), style = MaterialTheme.typography.bodyMedium)
    servers.forEach { server ->
        val id = (server["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val name = (server["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val protocol = (server["protocol"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        Surface(onClick = { onChoose(id) }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(CliRadius.control), color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(chosen == id, onClick = null, enabled = enabled)
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                com.foxhole.guard.ui.cli.components.CliProtocolBadge(protocol)
            }
        }
    }
}
