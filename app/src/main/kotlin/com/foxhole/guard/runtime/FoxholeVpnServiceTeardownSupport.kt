package com.foxhole.guard.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.NativeRuntimeSnapshot
import com.foxhole.core.runtime.RuntimeChildProcessReaper
import com.foxhole.core.runtime.RuntimeResumeStateStore
import com.foxhole.core.runtime.RuntimeStopPolicy
import com.foxhole.core.runtime.RuntimeStopResult
import com.foxhole.core.runtime.stopFailClosed
import com.foxhole.guard.R
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import com.foxhole.guard.ui.cli.CliMainActivity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Runtime stop / fail-closed teardown helpers for [FoxholeVpnService]. */

/**
 * THE teardown. Every path that ends a runtime session goes through here: disconnect, the
 * fail-closed stop, and both mode handoffs. Previously a copy-pasted litany whose copies had
 * drifted (handoffs missed auto-reconnect/heal cancels; some session fields were reset by no
 * path at all). What stays with the callers is what genuinely differs: persisting the final
 * traffic sample, awaiting the VPN network teardown, and the snapshot published afterwards.
 *
 * @param reason carried into the validation epoch, the runtime stop and the Tor process stop.
 * @param cancelHeal false for an ERROR teardown: a guard that failed to start on a no-network
 *   boot must keep its scheduled heal to retry when connectivity returns.
 * @param suspendNetworkActivityLogging true only while handing the guard over to a tunnel — the
 *   handoff's own churn must not be logged as the user's traffic.
 */
internal suspend fun FoxholeVpnService.closeRuntimeSession(
    reason: String,
    cancelHeal: Boolean = true,
    suspendNetworkActivityLogging: Boolean = false,
) {
    stopTrafficUpdates()
    stopAppTrafficStatsUpdates()
    stopGeoRefresh()
    stopNotificationHealthMonitoring()
    stopChildProcessWatchdog()
    cancelScheduledAutoReconnect(resetAttempts = true)
    if (cancelHeal) {
        cancelLocalGuardHeal(resetAttempts = true)
    }
    invalidateValidationEpoch(reason)
    stopRuntimeFailClosed(reason = reason)
    stopRuntimeHelpersIfNeeded(reason = reason)
    releaseRuntimeWakeLock()
    activeSession = null
    activeLocalGuardMode = null
    activeVpnNetworkHandle = null
    // Session-scoped leftovers: nothing reset these before, so a pending Tor upgrade outlived
    // its session and the ignored-loss handles piled up across reconnects.
    pendingTorRouteUpgradeSessionId = null
    ignoredVpnNetworkLossHandles.clear()
    upstreamNetworkHandles.clear()
    runtimeNetworkActivityLoggingSuspended = suspendNetworkActivityLogging
    RuntimeResumeStateStore.clear(this)
    container.connectionController.clearAppliedRuntime()
    bridgeWriter.updateActiveServerPingTarget(null)
    bridgeWriter.updateTraffic(trafficSampler.reset())
}

internal suspend fun FoxholeVpnService.stopActiveLocalGuardBeforeTunnelConnect(): Long? {
    val localGuardMode = activeLocalGuardMode ?: return null
    val localGuardVpnNetworkHandle = activeVpnNetworkHandle ?: currentVpnNetworkOrNull()?.networkHandle
    container.diagnosticsLogger.record(
        "connection",
        "local guard handoff to tunnel mode=${localGuardMode.name.lowercase()}",
    )
    closeRuntimeSession(
        reason = "local_guard_handoff",
        suspendNetworkActivityLogging = true,
    )
    // AFTER the close, which wipes the set: the guard's own tun is about to go away and its loss
    // must not read as the user losing the network.
    localGuardVpnNetworkHandle?.let(ignoredVpnNetworkLossHandles::add)
    runtimeInstanceStore.clear()
    container.diagnosticsLogger.record("runtime", "vpn runtime instance reset after local guard handoff")
    container.diagnosticsLogger.record("runtime", "network activity logging suspended for vpn handoff validation")
    return localGuardVpnNetworkHandle
}

internal suspend fun FoxholeVpnService.stopActiveTunnelBeforeLocalGuard(mode: LocalGuardMode) {
    val session = activeSession ?: return
    val tunnelVpnNetworkHandle = activeVpnNetworkHandle ?: currentVpnNetworkOrNull()?.networkHandle
    container.diagnosticsLogger.record(
        "connection",
        "tunnel handoff to local guard mode=${mode.name.lowercase()}",
    )
    persistProfileTraffic(session, trafficSampler.sample())
    // The torn-down tunnel may have been routing TOR_OVER_VPN; the local guard never uses Tor, so
    // the close stops the Tor process too or it would leak across the mode switch.
    closeRuntimeSession(reason = "local_guard_handoff_from_tunnel")
    // Mirror of the guard→tunnel path (also AFTER the close, which wipes the set): the tunnel tun
    // going away before guard-network tracking must not read as network loss — that produced a
    // false RECONNECTING over the starting guard and a parasitic heal.
    tunnelVpnNetworkHandle?.let(ignoredVpnNetworkLossHandles::add)
}

internal suspend fun FoxholeVpnService.stopRuntimeFailClosed(
    reason: String,
    policy: RuntimeStopPolicy? = null,
): RuntimeStopResult {
    val currentRuntime = runtimeInstanceStore.current()
    if (currentRuntime == null) {
        container.diagnosticsLogger.recordStructured(
            "runtime",
            "vpn runtime stop skipped without native owner",
            "reason=$reason",
        )
        val result =
            RuntimeStopResult(
                closeServiceOk = true,
                closeServerOk = true,
                tunClosed = true,
                escalatedToKill = false,
                elapsedMs = 0L,
            )
        val event = "stop_skipped_no_owner:$reason"
        lastRuntimeStopResourceEvent = event
        recordRuntimeResourceSnapshot(event = event)
        return result
    }
    val result =
        if (policy == null) {
            currentRuntime.stopFailClosed(
                owner = "vpn",
                reason = reason,
                diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
            )
        } else {
            currentRuntime.stopFailClosed(
                owner = "vpn",
                reason = reason,
                diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
                policy = policy,
            )
        }
    val event = if (result.graceful) "stop_success:$reason" else "stop_escalated:$reason"
    lastRuntimeStopResourceEvent = event
    recordRuntimeResourceSnapshot(
        event = event,
        async = false,
    )
    return result
}

@Suppress("ReturnCount")
internal suspend fun FoxholeVpnService.awaitStoppedVpnNetworkTeardown(
    previousVpnNetworkHandle: Long?,
    reason: String,
) {
    val handle = previousVpnNetworkHandle ?: return
    // ConnectivityManager's handle is the acceptance boundary: FoxCore owns a dup of the
    // VpnService descriptor, so a closed Kotlin ParcelFileDescriptor proves nothing about the
    // NetworkAgent. That shortcut once left a validated tun0 with full-device routes installed
    // indefinitely after a rare Tor-only Stop. Wait for the actual old handle to go.
    if (awaitVpnNetworkTeardownGracefulSettle(handle, reason)) {
        return
    }
    if (currentVpnNetworkOrNull()?.networkHandle != handle) {
        return
    }
    container.diagnosticsLogger.recordStructured(
        "connection",
        "vpn network teardown still pending",
        "reason=$reason",
        "previous_handle=$handle",
        "timeout_ms=${FoxholeVpnService.VPN_NETWORK_TEARDOWN_SETTLE_TIMEOUT_MS}",
    )
    // Fail-closed escalation: the VPN network survived the graceful stop, so the tun fd is still
    // open (e.g. the native engine holds an unreachable dup). Force-kill runs the native close,
    // then re-poll. Only fires on a genuinely stuck teardown — a reconnect installs a new handle
    // and settles the loop above first.
    forceKillRuntimeForStuckVpnTeardown(reason)
    if (awaitVpnNetworkTeardownAfterForceKill(handle, reason)) {
        return
    }
    terminateProcessIfTunnelStillUp(handle, reason)
}

// Polls the graceful-stop window; true only once Android removed or replaced the old VPN handle.
private suspend fun FoxholeVpnService.awaitVpnNetworkTeardownGracefulSettle(
    handle: Long,
    reason: String,
): Boolean {
    val deadline = SystemClock.elapsedRealtime() + FoxholeVpnService.VPN_NETWORK_TEARDOWN_SETTLE_TIMEOUT_MS
    while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < deadline) {
        val currentHandle = currentVpnNetworkOrNull()?.networkHandle
        if (currentHandle == null || currentHandle != handle) {
            container.diagnosticsLogger.recordStructured(
                "connection",
                "vpn network teardown settled",
                "reason=$reason",
                "previous_handle=$handle",
                currentHandle?.let { "current_handle=$it" } ?: "current_handle=null",
            )
            return true
        }
        delay(FoxholeVpnService.VPN_NETWORK_TEARDOWN_SETTLE_POLL_MS)
    }
    return false
}

private suspend fun FoxholeVpnService.forceKillRuntimeForStuckVpnTeardown(reason: String) {
    runtimeInstanceStore.current()?.let { runtime ->
        runCatching { runtime.forceKill("vpn_network_teardown_pending:$reason") }
            .onFailure {
                container.diagnosticsLogger.record(
                    "connection",
                    "vpn network teardown force-kill failed: ${it.message.orEmpty()}",
                )
            }
    }
}

private suspend fun FoxholeVpnService.awaitVpnNetworkTeardownAfterForceKill(
    handle: Long,
    reason: String,
): Boolean {
    val escalationDeadline =
        SystemClock.elapsedRealtime() + FoxholeVpnService.VPN_NETWORK_TEARDOWN_ESCALATION_TIMEOUT_MS
    while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < escalationDeadline) {
        if (currentVpnNetworkOrNull()?.networkHandle != handle) {
            container.diagnosticsLogger.recordStructured(
                "connection",
                "vpn network teardown settled after force-kill",
                "reason=$reason",
                "previous_handle=$handle",
            )
            return true
        }
        delay(FoxholeVpnService.VPN_NETWORK_TEARDOWN_SETTLE_POLL_MS)
    }
    return false
}

// Last resort: Android still owns the same VPN NetworkAgent after both bounded close windows.
// A LIVE tun retains full-device routes after Stop, so terminate the process — Android then
// revokes every descriptor, including FoxCore's dup. The kill is the fail-closed guarantee,
// but only for state it can actually fix.
private fun FoxholeVpnService.terminateProcessIfTunnelStillUp(
    handle: Long,
    reason: String,
) {
    if (currentVpnNetworkOrNull()?.networkHandle != handle) {
        return
    }
    val nativeSnapshot = runtimeInstanceStore.nativeSnapshot()
    val tunDescriptorProbe = processTunFileDescriptorProbe(nativeSnapshot.masterTunFd)
    // Live dev.60-opt repro: a fully clean native close with ConnectivityManager still reporting
    // the old VPN network for 8+ s. Killing there releases nothing (only NetworkAgent bookkeeping
    // lags) — it just crashes the app in the user's hands. The kill is reserved for a positively
    // observed descriptor in THIS process, or a snapshot still owning the engine/master TUN;
    // treating an unreadable /proc (UNKNOWN) as OPEN turns harmless framework lag into SIGKILL.
    if (!shouldTerminateProcessForStuckTunnel(nativeSnapshot, tunDescriptorProbe)) {
        container.diagnosticsLogger.recordStructured(
            "connection",
            "vpn network teardown lagging without held tun fd; skipping process kill",
            "reason=$reason",
            "previous_handle=$handle",
            "tun_probe=${tunDescriptorProbe.name.lowercase()}",
            "native_engine=${nativeSnapshot.hasEngineHandle}",
            "native_tun=${nativeSnapshot.hasTunFileDescriptor}",
            "master_fd=${nativeSnapshot.masterTunFd ?: "none"}",
            "foreign_fd=${lastForeignTunDescriptor ?: "none"}",
        )
        return
    }
    container.diagnosticsLogger.recordStructured(
        "connection",
        "vpn network teardown unresolved; terminating process to guarantee tunnel teardown",
        "reason=$reason",
        "previous_handle=$handle",
        "tun_probe=${tunDescriptorProbe.name.lowercase()}",
        "native_engine=${nativeSnapshot.hasEngineHandle}",
        "native_tun=${nativeSnapshot.hasTunFileDescriptor}",
        "master_fd=${nativeSnapshot.masterTunFd ?: "none"}",
        "foreign_fd=${lastForeignTunDescriptor ?: "none"}",
    )
    // The user must learn WHY the app vanished: the foreground notification dies with the process,
    // but a regular posted notification survives it.
    runCatching { postAbnormalTeardownNotification() }
    runCatching { removeForegroundNotification() }
    android.os.Process.killProcess(android.os.Process.myPid())
}

private fun FoxholeVpnService.postAbnormalTeardownNotification() {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val notificationManager = getSystemService(NotificationManager::class.java) ?: return
    notificationManager.createNotificationChannel(
        NotificationChannel(
            ABNORMAL_TEARDOWN_CHANNEL_ID,
            getString(R.string.abnormal_teardown_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            description = getString(R.string.abnormal_teardown_notification_channel_description)
        },
    )
    val body = getString(R.string.abnormal_teardown_notification_body)
    val openAppIntent =
        PendingIntent.getActivity(
            this,
            ABNORMAL_TEARDOWN_REQUEST_CODE_OPEN,
            Intent(this, CliMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val notification =
        NotificationCompat.Builder(this, ABNORMAL_TEARDOWN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_vpn)
            .setContentTitle(getString(R.string.abnormal_teardown_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()
    runCatching {
        NotificationManagerCompat.from(this).notify(ABNORMAL_TEARDOWN_NOTIFICATION_ID, notification)
    }
}

private const val ABNORMAL_TEARDOWN_CHANNEL_ID = "foxhole_connection_alerts"
private const val ABNORMAL_TEARDOWN_NOTIFICATION_ID = 9440
private const val ABNORMAL_TEARDOWN_REQUEST_CODE_OPEN = 214

/**
 * Process-local diagnostic for rapid-reconnect admission; deliberately not sufficient to accept
 * a completed disconnect — Android's NetworkAgent is the actual teardown boundary.
 */
internal enum class ProcessTunDescriptorProbe {
    OPEN,
    CLOSED,
    UNKNOWN,
}

/**
 * Is a `/dev/tun` descriptor open in this process besides the one held on purpose?
 * [ownMasterFd] is the master fd FoxCoreRuntime keeps while native code owns a duplicate (what
 * lets an outbound change replace the engine without dropping the OS VPN). Without excluding it
 * the probe reports the design as a leak — and the caller's answer to a leak is killing the
 * process (Pixel-diagnosed: a smart-profile protocol switch killed the app on candidate #3).
 */
/**
 * What the last OPEN verdict saw (`fd->target`). Diagnostic only: the kill decision must not
 * depend on a string.
 */
@Volatile
internal var lastForeignTunDescriptor: String? = null
    private set

internal fun processTunFileDescriptorProbe(ownMasterFd: Int? = null): ProcessTunDescriptorProbe {
    lastForeignTunDescriptor = null
    val descriptors =
        runCatching { java.io.File("/proc/self/fd").listFiles() }
            .getOrNull()
            ?: return ProcessTunDescriptorProbe.UNKNOWN
    if (descriptors.isEmpty()) {
        return ProcessTunDescriptorProbe.CLOSED
    }
    var readableDescriptors = 0
    descriptors.forEach { descriptor ->
        val target =
            runCatching { android.system.Os.readlink(descriptor.absolutePath) }
                .getOrNull()
                ?: return@forEach
        readableDescriptors += 1
        if (target.startsWith("/dev/tun") && descriptor.name.toIntOrNull() != ownMasterFd) {
            // Remembered so the caller can log WHICH descriptor it found: three diagnoses in a
            // row went wrong because the log said only "open" — the number is the one fact that
            // separates a leaked core dup from our own master.
            lastForeignTunDescriptor = "${descriptor.name}->$target"
            return ProcessTunDescriptorProbe.OPEN
        }
    }
    return if (readableDescriptors > 0) {
        ProcessTunDescriptorProbe.CLOSED
    } else {
        ProcessTunDescriptorProbe.UNKNOWN
    }
}

internal fun processHasOpenTunFileDescriptor(ownMasterFd: Int? = null): Boolean =
    processTunFileDescriptorProbe(ownMasterFd) != ProcessTunDescriptorProbe.CLOSED

internal fun shouldTerminateProcessForStuckTunnel(
    nativeSnapshot: NativeRuntimeSnapshot,
    tunDescriptorProbe: ProcessTunDescriptorProbe,
): Boolean =
    tunDescriptorProbe == ProcessTunDescriptorProbe.OPEN ||
        nativeSnapshot.hasEngineHandle ||
        nativeSnapshot.hasTunFileDescriptor

internal suspend fun FoxholeVpnService.stopRuntimeHelpersIfNeeded(reason: String) {
    // A native close that times out can still leave a managed pluggable transport behind. Reap
    // only the executable copied into this app's private native-library directory.
    val reaped = reapTorTransportOrphans()
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "arti transport helpers stopped",
        "reason=$reason",
        "transport_orphans_reaped=$reaped",
    )
    stopI2pdProcessIfNeeded(reason)
}

/**
 * Kills any surviving FoxCore-managed Tor transport. Executable and data root are app-private,
 * so the scan cannot match another application's process.
 */
private fun FoxholeVpnService.reapTorTransportOrphans(): Int {
    val executablePath = java.io.File(applicationInfo.nativeLibraryDir, TOR_TRANSPORT_LIBRARY_NAME).absolutePath
    val dataDirectoryRoot = java.io.File(filesDir, ARTI_DATA_ROOT_DIR_NAME).absolutePath
    return RuntimeChildProcessReaper(
        selfPid = android.os.Process.myPid(),
        killProcess = { pid -> android.os.Process.killProcess(pid) },
        diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
    ).reapOrphans(executablePath = executablePath, dataDirectoryRoot = dataDirectoryRoot)
}

private const val TOR_TRANSPORT_LIBRARY_NAME = "liblyrebird.so"
private const val ARTI_DATA_ROOT_DIR_NAME = "foxcore/arti"

internal suspend fun FoxholeVpnService.stopI2pdProcessIfNeeded(reason: String) {
    // Same fail-closed teardown for the i2pd child: stop() is a safe no-op when nothing runs, so
    // it also clears an i2pd process orphaned by an earlier session.
    container.i2pdManager.stop()
    // Belt-and-suspenders: framework-kill any surviving i2pd child by its own executable / data-dir
    // identity. i2pd's SIGTERM handler could wedge the JVM destroy ladder and leave an orphan alive
    // (device-confirmed), which then blocked the next runtime start.
    val reaped = reapI2pdOrphans()
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "i2pd process stopped",
        "reason=$reason",
        "orphans_reaped=$reaped",
    )
}

private fun FoxholeVpnService.reapI2pdOrphans(): Int {
    val executablePath = java.io.File(applicationInfo.nativeLibraryDir, I2PD_NATIVE_LIBRARY_NAME).absolutePath
    val dataDirectoryRoot = java.io.File(filesDir, I2PD_DATA_ROOT_DIR_NAME).absolutePath
    return RuntimeChildProcessReaper(
        selfPid = android.os.Process.myPid(),
        killProcess = { pid -> android.os.Process.killProcess(pid) },
        diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
    ).reapOrphans(executablePath = executablePath, dataDirectoryRoot = dataDirectoryRoot)
}

private const val I2PD_NATIVE_LIBRARY_NAME = "libi2pd.so"
private const val I2PD_DATA_ROOT_DIR_NAME = "i2pd-data"
