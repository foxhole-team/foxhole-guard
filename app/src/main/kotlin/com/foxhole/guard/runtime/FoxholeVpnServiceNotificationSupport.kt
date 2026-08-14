package com.foxhole.guard.runtime

import android.app.Notification
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.LanProxyPhase
import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.NotificationSnapshot
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.RuntimeCommand
import com.foxhole.core.runtime.RuntimeCommandPriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground-service notification + runtime-command dispatch for [FoxholeVpnService], extracted from
 * the service body in the Phase B split by responsibility.
 */

/**
 * Keeps the pull-built Android notification in step with the same confirmed I2P phase StateFlow
 * consumed by the app UI. The initial value is deliberately ignored: [FoxholeVpnService.onCreate]
 * must retain its graph-free fast foreground claim; onStartCommand builds the first full snapshot.
 */
internal fun FoxholeVpnService.startI2pNotificationPhaseMonitoring() {
    scope.launch {
        collectI2pNotificationPhaseChanges(container.connectionController.i2pPhase) {
            updateNotification()
        }
    }
}

/** Rebuilds the standard notification from the same confirmed Tor phase consumed by the UI. */
internal fun FoxholeVpnService.startTorNotificationPhaseMonitoring() {
    scope.launch {
        collectTorNotificationPhaseChanges(container.connectionController.torPhase) {
            updateNotification()
        }
    }
}

/**
 * The LAN-proxy add-on is toggled without restarting the Android foreground service. Rebuild the
 * pull-based notification when the core confirms that its listener appeared or disappeared, so
 * disabling the add-on cannot leave a stale "proxy server available" line behind.
 */
internal fun FoxholeVpnService.startLanProxyNotificationPhaseMonitoring() {
    scope.launch {
        collectLanProxyNotificationPhaseChanges(container.connectionController.lanProxyStatus) {
            updateNotification()
        }
    }
}

internal suspend fun collectI2pNotificationPhaseChanges(
    snapshots: Flow<I2pPhaseSnapshot>,
    onPhaseChanged: (I2pNetworkPhase) -> Unit,
) {
    snapshots
        .map { snapshot -> snapshot.phase }
        .distinctUntilChanged()
        .drop(1)
        .collect(onPhaseChanged)
}

internal suspend fun collectTorNotificationPhaseChanges(
    snapshots: Flow<TorPhaseSnapshot>,
    onPhaseChanged: (TorNetworkPhase) -> Unit,
) {
    snapshots
        .map { snapshot -> snapshot.phase }
        .distinctUntilChanged()
        .drop(1)
        .collect(onPhaseChanged)
}

internal suspend fun collectLanProxyNotificationPhaseChanges(
    snapshots: Flow<LanProxyStatusSnapshot>,
    onPhaseChanged: (LanProxyPhase) -> Unit,
) {
    snapshots
        .map { snapshot -> snapshot.phase }
        .distinctUntilChanged()
        .drop(1)
        .collect(onPhaseChanged)
}

internal fun FoxholeVpnService.launchCommand(
    reason: String,
    block: suspend () -> Unit,
) {
    runtimeSupervisor.launch(
        priority = RuntimeCommandPriority.NORMAL,
        reason = reason,
        owner = runtimeCommandOwner,
        block = block,
    )
}

internal fun FoxholeVpnService.launchPriorityCommand(
    priority: RuntimeCommandPriority,
    reason: String,
    block: suspend () -> Unit,
) {
    runtimeSupervisor.launch(
        priority = priority,
        reason = reason,
        owner = runtimeCommandOwner,
        block = block,
    )
}

internal fun FoxholeVpnService.dispatchRuntimeCommand(
    command: RuntimeCommand,
    block: suspend (RuntimeCommand) -> Unit,
) {
    runtimeSupervisor.dispatch(
        command = command,
        owner = runtimeCommandOwner,
        execute = block,
    )
}

internal fun FoxholeVpnService.stopService(commandStartId: Int?) {
    // Without a startId (an error teardown from validation or health) the stop uses the freshest
    // known one: an unconditional stopSelf() ignored a newer CONNECT intent and killed the service
    // together with a user command already queued.
    val effectiveStartId = commandStartId ?: latestServiceStartId
    if (effectiveStartId > 0) {
        stopSelfResult(effectiveStartId)
    } else {
        stopSelf()
    }
}

internal fun FoxholeVpnService.buildNotification(snapshot: NotificationSnapshot): Notification =
    buildConnectionNotification(
        mode = TrafficMode.TUNNEL,
        snapshot = snapshot,
        collapsedText = ::notificationCollapsedText,
        expandedText = ::notificationExpandedText,
        stateLabel = ::notificationStateLabel,
        smallIconRes = notificationSmallIconRes(snapshot),
        showAction = activeLocalGuardMode == null,
    )

internal fun FoxholeVpnService.updateNotification() {
    updateConnectionNotification { buildNotification(currentNotificationSnapshot()) }
}
