package com.foxhole.guard.router.ui

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterEngineControlState
import com.foxhole.guard.router.RouterEngineFailureReason
import com.foxhole.guard.router.RouterEngineImpact
import com.foxhole.guard.router.RouterEngineJob
import com.foxhole.guard.router.RouterEngineJobState
import com.foxhole.guard.router.RouterEngineOperation
import com.foxhole.guard.router.RouterEnginePlan
import com.foxhole.guard.router.RouterEngineStorage
import com.foxhole.guard.router.RouterInstallProtocol

@Composable
internal fun GuardRouterProtocolsList(state: RouterEngineControlState, selected: Set<RouterInstallProtocol>,
    listState: LazyListState, enabled: Boolean, onSelect: (Set<RouterInstallProtocol>) -> Unit,
    onUpdate: (RouterInstallProtocol) -> Unit, onRemove: (RouterInstallProtocol) -> Unit, onShowJob: () -> Unit) {
    val status = state.status
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (status == null) item { Text(stringResource(R.string.guard_router_router_engines_unavailable)) }
        if (state.running) item { Text(stringResource(R.string.guard_router_router_engines_busy)) }
        if (state.job != null) item {
            OutlinedButton(onShowJob, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(if (state.job.running) R.string.guard_router_router_engines_show_progress else R.string.guard_router_router_engines_show_result))
            }
        }
        val installed = status?.entries.orEmpty().filter { it.installed }
        if (installed.isNotEmpty()) item {
            Text(stringResource(R.string.guard_router_router_engines_installed), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        }
        items(installed, key = { it.protocol.canonical }) { entry ->
            GuardRouterTableRow(Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(R.drawable.lin_power), null, Modifier.size(20.dp))
                        Text(entry.protocol.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(listOfNotNull(entry.version, stringResource(if (entry.active) R.string.guard_router_router_engines_in_use else R.string.guard_router_router_engines_installed))
                        .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = LocalGuardRouterColors.current.muted)
                }
                if (entry.canUpdate) OutlinedButton(onClick = { onUpdate(entry.protocol) }, enabled = enabled && !state.running) {
                    Text(stringResource(R.string.guard_router_router_engines_update))
                }
                IconButton(onClick = { onRemove(entry.protocol) }, enabled = enabled && !state.running && !entry.active) {
                    Icon(painterResource(R.drawable.lin_trash), stringResource(R.string.guard_router_router_engines_remove_protocol, entry.protocol.label),
                        Modifier.size(22.dp), tint = guardRouterDeleteColor())
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        val available = status?.entries.orEmpty().filter { it.canAdd }
        if (status != null && installed.isEmpty() && available.isEmpty()) item {
            Text(stringResource(R.string.guard_router_router_engines_not_released))
        }
        if (available.isNotEmpty()) item {
            var expanded by remember(status?.catalogSha256, status?.revision) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.guard_router_router_engines_add), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                val choose = stringResource(R.string.guard_router_vpn_router_choose_protocols)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val menuWidth = maxWidth
                    OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled && !state.running,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(painterResource(R.drawable.lin_power), null, Modifier.size(20.dp))
                        Text(available.filter { it.protocol in selected }.joinToString(" · ") { it.protocol.label }
                            .ifBlank { choose }, Modifier.weight(1f))
                        Icon(painterResource(R.drawable.lin_arrow_down), null, Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded && enabled && !state.running, onDismissRequest = { expanded = false },
                        modifier = Modifier.width(menuWidth).heightIn(max = 320.dp)) {
                        available.forEach { entry ->
                            val checked = entry.protocol in selected
                            DropdownMenuItem(text = { Text(entry.protocol.label) }, trailingIcon = { Checkbox(checked, onCheckedChange = null) },
                                onClick = { onSelect(if (checked) selected - entry.protocol else selected + entry.protocol) },
                                modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GuardRouterProtocolPlan(plan: RouterEnginePlan?) {
    if (plan == null) { Text(stringResource(R.string.guard_router_router_engines_stale)); return }
    val context = LocalContext.current
    if (plan.operation == RouterEngineOperation.REMOVE) Text(stringResource(R.string.guard_router_router_engines_remove_confirm))
    val budgetRows = mutableListOf<Pair<String, String>>()
    if (plan.operation != RouterEngineOperation.REMOVE) {
        if (plan.changes.any { it.storage == RouterEngineStorage.FLASH }) budgetRows +=
            stringResource(R.string.guard_router_router_engines_flash_budget) to stringResource(R.string.guard_router_router_engines_memory_budget,
                Formatter.formatShortFileSize(context, plan.budget.flashRequiredBytes), Formatter.formatShortFileSize(context, plan.budget.flashAvailableBytes))
        if (plan.changes.any { it.storage == RouterEngineStorage.RAM }) budgetRows +=
            stringResource(R.string.guard_router_router_engines_ram_budget) to stringResource(R.string.guard_router_router_engines_memory_budget,
                Formatter.formatShortFileSize(context, plan.budget.ramRequiredBytes), Formatter.formatShortFileSize(context, plan.budget.ramAvailableBytes))
    }
    GuardRouterReviewTable(plan.changes.map { change ->
        val version = listOfNotNull(change.fromVersion, change.toVersion).distinct().joinToString(" → ")
        change.protocol.label to listOf(version, stringResource(if (change.storage == RouterEngineStorage.RAM)
            R.string.guard_router_vpn_router_ram else R.string.guard_router_vpn_router_flash)).filter { it.isNotBlank() }.joinToString(" · ")
    } + listOf(stringResource(R.string.guard_router_router_engines_download_size) to Formatter.formatShortFileSize(context, plan.budget.downloadBytes)) + budgetRows)
    if (plan.changes.any { it.storage == RouterEngineStorage.RAM } && plan.operation != RouterEngineOperation.REMOVE)
        Text(stringResource(R.string.guard_router_vpn_router_ram_note), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(if (plan.impact == RouterEngineImpact.RECONNECT_REQUIRED) R.string.guard_router_router_engines_reconnect_note
        else R.string.guard_router_router_engines_keep_active), style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun GuardRouterProtocolJob(job: RouterEngineJob?) {
    if (job == null) { Text(stringResource(R.string.guard_router_router_engines_unavailable)); return }
    val progress by animateFloatAsState(job.progressPercent / 100f, GuardRouterSheetMotion.fastEffectsSpec(), label = "router-engine-progress")
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.guard_router_router_review_progress_percent, job.progressPercent), style = MaterialTheme.typography.labelLarge)
    Text(stringResource(when (job.state) {
        RouterEngineJobState.QUEUED -> R.string.guard_router_router_engines_queued
        RouterEngineJobState.DOWNLOADING -> R.string.guard_router_router_review_progress_download
        RouterEngineJobState.VERIFYING -> R.string.guard_router_router_engines_verifying
        RouterEngineJobState.INSTALLING -> R.string.guard_router_router_engines_installing
        RouterEngineJobState.CHECKING -> R.string.guard_router_router_review_progress_check
        RouterEngineJobState.RECONNECTING -> R.string.guard_router_router_engines_reconnecting
        RouterEngineJobState.SUCCEEDED -> R.string.guard_router_router_engines_done
        RouterEngineJobState.FAILED -> R.string.guard_router_router_engines_failed
        RouterEngineJobState.RECOVERY_REQUIRED -> R.string.guard_router_router_engines_recovery
    }))
    if (job.running) Text(stringResource(R.string.guard_router_router_engines_close_progress), style = MaterialTheme.typography.bodySmall,
        color = LocalGuardRouterColors.current.muted)
    if (job.previousConnectionRestored == true) Text(stringResource(R.string.guard_router_router_engines_restored), style = MaterialTheme.typography.bodySmall)
}

internal fun routerEngineFailureLabel(reason: RouterEngineFailureReason): Int = when (reason) {
    RouterEngineFailureReason.STALE_PLAN, RouterEngineFailureReason.STALE_SESSION -> R.string.guard_router_router_engines_stale
    RouterEngineFailureReason.NOT_RELEASED, RouterEngineFailureReason.UNSUPPORTED_TARGET -> R.string.guard_router_router_engines_not_released
    RouterEngineFailureReason.IN_USE -> R.string.guard_router_router_engines_in_use
    RouterEngineFailureReason.BUSY -> R.string.guard_router_router_engines_busy
    RouterEngineFailureReason.NO_SPACE -> R.string.guard_router_router_engines_no_space
    RouterEngineFailureReason.UNSUPPORTED_PROFILE_GRAPH -> R.string.guard_router_router_engines_profile_graph
    RouterEngineFailureReason.RECONNECT_FAILED, RouterEngineFailureReason.RECOVERY_REQUIRED -> R.string.guard_router_router_engines_recovery
    RouterEngineFailureReason.DOWNLOAD_FAILED -> R.string.guard_router_router_engines_download_failed
    RouterEngineFailureReason.PACKAGE_VERIFICATION_FAILED, RouterEngineFailureReason.CANDIDATE_CHECK_FAILED -> R.string.guard_router_router_engines_verification_failed
    else -> R.string.guard_router_router_engines_failed
}
