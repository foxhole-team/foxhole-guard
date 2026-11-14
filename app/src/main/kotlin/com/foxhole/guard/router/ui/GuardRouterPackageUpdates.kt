package com.foxhole.guard.router.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliPixelProgressBar
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterEngineImpact
import com.foxhole.guard.router.RouterUpdateAvailability
import com.foxhole.guard.router.RouterUpdateControlState
import com.foxhole.guard.router.RouterUpdateFailureReason
import com.foxhole.guard.router.RouterUpdateOperation

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
internal fun GuardRouterPackageUpdates(state: RouterUpdateControlState, enabled: Boolean, onAuto: (Boolean) -> Unit,
    checking: Boolean = false) {
    val status = state.status
    val failure = if (state.deviceCheckedCurrent) null else status?.autoUpdate?.suspendedReason ?: status?.failureReason ?: state.failure
    val statusLabel = when {
        failure != null -> updateFailureLabel(failure)
        state.unreachable -> R.string.guard_router_router_updates_unreachable
        else -> updateAvailabilityLabel(state, checking)
    }
    Text(stringResource(statusLabel), style = MaterialTheme.typography.titleSmall,
        color = if (failure != null || state.unreachable) MaterialTheme.colorScheme.error else androidx.compose.material3.LocalContentColor.current,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    status?.takeIf { it.availability == RouterUpdateAvailability.AVAILABLE }?.availableVersion?.let {
        Text(stringResource(R.string.guard_router_router_updates_available_version, it), style = MaterialTheme.typography.bodyMedium)
    }
    val progress = state.job?.progress?.takeIf { it.running && it.operation == RouterUpdateOperation.APPLY }
        ?: status?.activeAutomaticJob?.takeIf { it.running && it.operation == RouterUpdateOperation.APPLY }
    if (progress != null) {
        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CliPixelProgressBar(fraction = progress.progressPercent / 100f,
                modifier = Modifier.fillMaxWidth().semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress.progressPercent / 100f, 0f..1f)
                })
            Text(stringResource(R.string.guard_router_router_updates_progress, progress.progressPercent))
        }
    }
    if (state.canApply && state.plan?.impact == RouterEngineImpact.RECONNECT_REQUIRED) {
        Text(stringResource(R.string.guard_router_router_updates_reconnect_note), style = MaterialTheme.typography.bodySmall)
    }
    val autoLabel = stringResource(R.string.guard_router_router_updates_auto)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically) {
        Text(autoLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        GuardRouterSwitch(checked = status?.autoUpdate?.enabled == true, onCheckedChange = onAuto, enabled = enabled && state.canSetAuto,
            modifier = Modifier.semantics { contentDescription = autoLabel })
    }
}

@Composable
internal fun GuardRouterPackageUpdateFooter(state: RouterUpdateControlState, enabled: Boolean, onCheck: () -> Unit,
    onApply: () -> Unit, onRefresh: () -> Unit) {
    val refresh = state.running || state.unreachable || state.supported == null ||
        state.status?.availability == RouterUpdateAvailability.AVAILABLE && state.plan == null
    CliButton(
        label = stringResource(if (state.canApply) R.string.guard_router_router_updates_apply else R.string.guard_router_router_updates_check),
        onClick = when { state.canApply -> onApply; refresh -> onRefresh; else -> onCheck },
        modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight),
        enabled = enabled && (state.canApply || refresh || state.canCheck),
        filled = false,
    )
}

private fun updateAvailabilityLabel(state: RouterUpdateControlState, checking: Boolean = false): Int = when {
    state.deviceDownloading || checking -> R.string.guard_router_router_updates_checking
    state.deviceCheckedCurrent -> R.string.guard_router_router_updates_current
    state.pending?.operation == RouterUpdateOperation.CHECK ||
        state.job?.progress?.let { it.running && it.operation == RouterUpdateOperation.CHECK } == true ||
        state.status?.activeAutomaticJob?.let { it.running && it.operation == RouterUpdateOperation.CHECK } == true -> R.string.guard_router_router_updates_checking
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
