package com.foxhole.guard.runtime

import android.app.Notification
import com.foxhole.core.model.NotificationSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.RuntimeCommand
import com.foxhole.core.runtime.RuntimeCommandPriority

/**
 * Foreground-service notification + runtime-command dispatch for [FoxholeVpnService], extracted from
 * the service body in the Phase B split by responsibility.
 */

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
