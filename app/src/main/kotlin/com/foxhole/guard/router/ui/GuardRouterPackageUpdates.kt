package com.foxhole.guard.router.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterEngineImpact
import com.foxhole.guard.router.RouterEngineJobState
import com.foxhole.guard.router.RouterUpdateAvailability
import com.foxhole.guard.router.RouterUpdateControlState
import com.foxhole.guard.router.RouterUpdateFailureReason
import com.foxhole.guard.router.RouterUpdateStep

@Composable
internal fun GuardRouterPackageUpdateRow(state: RouterUpdateControlState, enabled: Boolean, onOpen: () -> Unit) {
    GuardRouterTableRow(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen)) {
        Icon(painterResource(R.drawable.lin_restart), null, Modifier.size(20.dp))
        Text(stringResource(R.string.guard_router_router_updates_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(0.75f), horizontalAlignment = Alignment.End) {
            state.status?.currentVersion?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(updateAvailabilityLabel(state)), style = MaterialTheme.typography.bodySmall,
                color = LocalGuardRouterColors.current.muted)
        }
        Icon(painterResource(R.drawable.lin_arrow_right), null, Modifier.size(20.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun GuardRouterPackageUpdates(state: RouterUpdateControlState, enabled: Boolean, onAuto: (Boolean) -> Unit) {
    val status = state.status
    Text(stringResource(R.string.guard_router_router_updates_scope), style = MaterialTheme.typography.bodyMedium)
    if (status != null) {
        status.currentVersion?.let { Text(stringResource(R.string.guard_router_router_updates_current_version, it)) }
        status.availableVersion?.let { Text(stringResource(R.string.guard_router_router_updates_available_version, it)) }
    }
    Text(stringResource(updateAvailabilityLabel(state)), style = MaterialTheme.typography.titleSmall)
    if (state.pending != null || state.reconnecting) Text(stringResource(R.string.guard_router_router_updates_uncertain))
    if (state.unreachable) Text(stringResource(R.string.guard_router_router_updates_unreachable))
    val progress = state.job?.progress?.takeIf { it.running } ?: status?.activeAutomaticJob ?:
        state.job?.progress?.takeIf { state.pending == null && !state.reconnecting }
    if (progress != null) {
        if (progress.automatic) Text(stringResource(R.string.guard_router_router_updates_automatic_job))
        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(updateStepLabel(progress.step)))
            LinearProgressIndicator(progress = { progress.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.guard_router_router_updates_progress, progress.progressPercent))
        }
        if (progress.state == RouterEngineJobState.SUCCEEDED) Text(stringResource(R.string.guard_router_router_updates_done))
        if (progress?.automatic == false && state.job?.previousConnectionRestored == true) Text(stringResource(R.string.guard_router_router_engines_restored))
    }
    state.plan?.let { plan ->
        Text(stringResource(R.string.guard_router_router_updates_changes), style = MaterialTheme.typography.titleSmall)
        plan.changes.forEach { change ->
            GuardRouterTableRow(Modifier.fillMaxWidth()) {
                Text(if (change.id == "foxhole") "FoxHole" else when (change.id) {
                    "hysteria2" -> "Hysteria2"; "vless" -> "VLESS"; else -> "WireGuard"
                }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.guard_router_router_updates_version_change, change.fromVersion, change.toVersion),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(if (plan.impact == RouterEngineImpact.RECONNECT_REQUIRED) R.string.guard_router_router_updates_reconnect_note
            else R.string.guard_router_router_engines_keep_active), style = MaterialTheme.typography.bodySmall)
    }
    val failure = status?.autoUpdate?.suspendedReason ?: status?.failureReason ?: state.failure
    failure?.let { Text(stringResource(updateFailureLabel(it)), color = MaterialTheme.colorScheme.error) }
    val auto = status?.autoUpdate
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {}.toggleable(value = auto?.enabled == true,
        enabled = enabled && state.canSetAuto, role = Role.Checkbox, onValueChange = onAuto), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = auto?.enabled == true, onCheckedChange = null, enabled = enabled && state.canSetAuto)
        Text(stringResource(R.string.guard_router_router_updates_auto), style = MaterialTheme.typography.bodyMedium)
    }
    Text(stringResource(R.string.guard_router_router_updates_auto_help), style = MaterialTheme.typography.bodySmall, color = LocalGuardRouterColors.current.muted)
}

@Composable
internal fun GuardRouterPackageUpdateFooter(state: RouterUpdateControlState, enabled: Boolean, onCheck: () -> Unit,
    onApply: () -> Unit, onRefresh: () -> Unit) {
    if (state.canApply) Button(onApply, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.guard_router_router_updates_apply))
    }
    if (state.running || state.unreachable || state.supported == null ||
        state.status?.availability == RouterUpdateAvailability.AVAILABLE && state.plan == null) OutlinedButton(onRefresh, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.guard_router_router_updates_status)) }
    else OutlinedButton(onCheck, enabled = enabled && state.canCheck, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.guard_router_router_updates_check))
    }
}

private fun updateAvailabilityLabel(state: RouterUpdateControlState): Int = when {
    state.pending != null || state.reconnecting -> R.string.guard_router_router_updates_waiting
    state.job?.running == true || state.status?.busy == true || state.status?.activeAutomaticJob?.running == true -> R.string.guard_router_router_updates_running
    state.supported == false -> R.string.guard_router_router_updates_unsupported
    else -> when (state.status?.availability) {
        RouterUpdateAvailability.CURRENT -> R.string.guard_router_router_updates_current
        RouterUpdateAvailability.AVAILABLE -> R.string.guard_router_router_updates_available
        RouterUpdateAvailability.UNSUPPORTED -> R.string.guard_router_router_updates_unsupported
        RouterUpdateAvailability.CHECK_FAILED -> R.string.guard_router_router_updates_check_failed
        else -> R.string.guard_router_router_updates_not_checked
    }
}

private fun updateStepLabel(step: RouterUpdateStep): Int = when (step) {
    RouterUpdateStep.PREPARING -> R.string.guard_router_router_updates_step_preparing
    RouterUpdateStep.CHECKING_RELEASE -> R.string.guard_router_router_updates_step_release
    RouterUpdateStep.DOWNLOADING_INDEX -> R.string.guard_router_router_updates_step_index
    RouterUpdateStep.DOWNLOADING_PACKAGE -> R.string.guard_router_router_updates_step_download
    RouterUpdateStep.VERIFYING_PACKAGE -> R.string.guard_router_router_updates_step_verifying
    RouterUpdateStep.BACKING_UP -> R.string.guard_router_router_updates_step_backup
    RouterUpdateStep.INSTALLING_PACKAGE -> R.string.guard_router_router_updates_step_installing
    RouterUpdateStep.CHECKING_PACKAGE -> R.string.guard_router_router_updates_step_check
    RouterUpdateStep.RECONNECTING_VPN -> R.string.guard_router_router_updates_step_reconnect
    RouterUpdateStep.COMPLETED -> R.string.guard_router_router_updates_done
    RouterUpdateStep.FAILED -> R.string.guard_router_router_engines_failed
    RouterUpdateStep.RECOVERY_REQUIRED -> R.string.guard_router_router_engines_recovery
}

private fun updateFailureLabel(reason: RouterUpdateFailureReason): Int = when (reason) {
    RouterUpdateFailureReason.UNSUPPORTED_TARGET -> R.string.guard_router_router_updates_unsupported
    RouterUpdateFailureReason.NOT_CHECKED -> R.string.guard_router_router_updates_not_checked
    RouterUpdateFailureReason.RELEASE_UNAVAILABLE -> R.string.guard_router_router_updates_release_unavailable
    RouterUpdateFailureReason.AUTHENTICATION_FAILED -> R.string.guard_router_router_updates_auth_failed
    RouterUpdateFailureReason.STALE_PLAN, RouterUpdateFailureReason.STALE_SESSION -> R.string.guard_router_router_updates_stale
    RouterUpdateFailureReason.BUSY -> R.string.guard_router_router_engines_busy
    RouterUpdateFailureReason.NO_SPACE -> R.string.guard_router_router_updates_no_space
    RouterUpdateFailureReason.DOWNLOAD_FAILED -> R.string.guard_router_router_updates_download_failed
    RouterUpdateFailureReason.PACKAGE_VERIFICATION_FAILED, RouterUpdateFailureReason.DOWNGRADE_REJECTED,
    RouterUpdateFailureReason.VERSION_IDENTITY_CONFLICT -> R.string.guard_router_router_updates_verification_failed
    RouterUpdateFailureReason.ROLLBACK_UNAVAILABLE -> R.string.guard_router_router_updates_rollback_unavailable
    RouterUpdateFailureReason.RAM_STAGE_UNAVAILABLE -> R.string.guard_router_router_updates_ram_unavailable
    RouterUpdateFailureReason.ROUTER_UNAVAILABLE -> R.string.guard_router_router_updates_unreachable
    RouterUpdateFailureReason.RECOVERY_REQUIRED -> R.string.guard_router_router_engines_recovery
    RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE -> R.string.guard_router_router_updates_disconnect_before_update
    RouterUpdateFailureReason.INVALID_REQUEST -> R.string.guard_router_router_updates_invalid_response
}
