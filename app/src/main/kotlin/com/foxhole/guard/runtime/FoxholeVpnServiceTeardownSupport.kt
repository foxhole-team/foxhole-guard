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
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.RuntimeTeardownPhase
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.FoxholeRuntime
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.NativeRuntimeSnapshot
import com.foxhole.core.runtime.RuntimeChildProcessReaper
import com.foxhole.core.runtime.RuntimeResumeStateStore
import com.foxhole.core.runtime.RuntimeStopPolicy
import com.foxhole.core.runtime.RuntimeStopResult
import com.foxhole.core.runtime.establishNextInterfaceThenStopRetired
import com.foxhole.core.runtime.stopEveryRetiredRuntime
import com.foxhole.core.runtime.stopFailClosed
import com.foxhole.guard.R
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import com.foxhole.guard.ui.cli.CliMainActivity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Runtime stop / fail-closed teardown helpers for [FoxholeVpnService]. */

/** Whether teardown closes the TUN or parks a quiesced owner for replacement. */
internal enum class RuntimeInterfaceDisposition {
    STOP,
    RETIRE_FOR_HANDOVER,
}

internal data class RuntimeHandoverPreparation(
    val ready: Boolean,
    val previousVpnNetworkHandle: Long?,
    val previousVpnInterfaceName: String?,
)

internal data class RuntimeHandoverCompletion<T>(
    val value: T,
    /** True only when every retired native owner proved that its retained master TUN was closed. */
    val retiredTunClosed: Boolean,
)

private data class ActiveVpnNetworkIdentity(
    val handle: Long?,
    val interfaceName: String?,
)

private fun FoxholeVpnService.activeVpnNetworkIdentity(): ActiveVpnNetworkIdentity {
    val network = currentVpnNetworkOrNull()
    return ActiveVpnNetworkIdentity(
        handle = activeVpnNetworkHandle ?: network?.networkHandle,
        interfaceName = network?.let(connectivityManager::getLinkProperties)?.interfaceName,
    )
}

/** Shared teardown for disconnects and interface handoffs. */
internal suspend fun FoxholeVpnService.closeRuntimeSession(
    reason: String,
    cancelHeal: Boolean = true,
    suspendNetworkActivityLogging: Boolean = false,
    interfaceDisposition: RuntimeInterfaceDisposition = RuntimeInterfaceDisposition.STOP,
    onTeardownPhase: ((RuntimeTeardownPhase) -> Unit)? = null,
): Boolean {
    if (
        interfaceDisposition == RuntimeInterfaceDisposition.RETIRE_FOR_HANDOVER &&
        !retireRuntimeForInterfaceHandover(reason)
    ) {
        return false
    }
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
    if (interfaceDisposition == RuntimeInterfaceDisposition.STOP) {
        container.i2pdManager.markCarrierUnavailable()
        onTeardownPhase?.invoke(RuntimeTeardownPhase.VPN)
        stopRuntimeFailClosed(reason = reason)
        stopRetiredRuntimes(reason)
        stopRuntimeHelpersIfNeeded(reason = reason, onTeardownPhase = onTeardownPhase)
    }
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
    return true
}

/** Stops native packet processing, retains the master TUN, then parks the runtime. */
internal suspend fun FoxholeVpnService.retireRuntimeForInterfaceHandover(reason: String): Boolean {
    if (!runtimeInstanceStore.quiesceAndRetireCurrent()) {
        container.diagnosticsLogger.recordFailure(
            "runtime",
            "native runtime could not quiesce for interface handover reason=$reason",
        )
        return false
    }
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "vpn runtime quiesced and retired for interface handover",
        "reason=$reason",
    )
    return true
}

/** Prepares local guard → tunnel without releasing the old master TUN. */
internal suspend fun FoxholeVpnService.retireActiveLocalGuardForTunnelHandover(): RuntimeHandoverPreparation {
    val localGuardMode =
        activeLocalGuardMode
            ?: return RuntimeHandoverPreparation(
                ready = true,
                previousVpnNetworkHandle = null,
                previousVpnInterfaceName = null,
            )
    val previousVpnIdentity = activeVpnNetworkIdentity()
    val localGuardVpnNetworkHandle = previousVpnIdentity.handle
    container.diagnosticsLogger.record(
        "connection",
        "local guard handoff to tunnel mode=${localGuardMode.name.lowercase()}",
    )
    val prepared = closeRuntimeSession(
        reason = "local_guard_handoff",
        suspendNetworkActivityLogging = true,
        interfaceDisposition = RuntimeInterfaceDisposition.RETIRE_FOR_HANDOVER,
    )
    if (!prepared) {
        return RuntimeHandoverPreparation(
            ready = false,
            previousVpnNetworkHandle = localGuardVpnNetworkHandle,
            previousVpnInterfaceName = previousVpnIdentity.interfaceName,
        )
    }
    // AFTER the close, which wipes the set: the guard's own tun is about to be replaced and its
    // loss must not read as the user losing the network.
    localGuardVpnNetworkHandle?.let(ignoredVpnNetworkLossHandles::add)
    container.diagnosticsLogger.record("runtime", "network activity logging suspended for vpn handoff validation")
    return RuntimeHandoverPreparation(
        ready = true,
        previousVpnNetworkHandle = localGuardVpnNetworkHandle,
        previousVpnInterfaceName = previousVpnIdentity.interfaceName,
    )
}

/** Mirror of [retireActiveLocalGuardForTunnelHandover] for the tunnel → local guard direction. */
internal suspend fun FoxholeVpnService.retireActiveTunnelForLocalGuardHandover(
    mode: LocalGuardMode,
): RuntimeHandoverPreparation {
    val session = activeSession ?: return RuntimeHandoverPreparation(
        ready = true,
        previousVpnNetworkHandle = null,
        previousVpnInterfaceName = null,
    )
    val previousVpnIdentity = activeVpnNetworkIdentity()
    val tunnelVpnNetworkHandle = previousVpnIdentity.handle
    container.diagnosticsLogger.record(
        "connection",
        "tunnel handoff to local guard mode=${mode.name.lowercase()}",
    )
    persistProfileTraffic(session, trafficSampler.sample())
    // The retired tunnel may have been routing TOR_OVER_VPN and the local guard never uses Tor, so
    // its Tor process must not survive the switch. It cannot be stopped here any more: the tunnel
    // is still carrying traffic. [stopRetiredRuntimes] stops the runtime and reaps its transports
    // in one step, once the guard's interface is up.
    val prepared = closeRuntimeSession(
        reason = "local_guard_handoff_from_tunnel",
        interfaceDisposition = RuntimeInterfaceDisposition.RETIRE_FOR_HANDOVER,
    )
    if (!prepared) {
        return RuntimeHandoverPreparation(
            ready = false,
            previousVpnNetworkHandle = tunnelVpnNetworkHandle,
            previousVpnInterfaceName = previousVpnIdentity.interfaceName,
        )
    }
    // Mirror of the guard→tunnel path (also AFTER the close, which wipes the set): the tunnel tun
    // going away before guard-network tracking must not read as network loss — that produced a
    // false RECONNECTING over the starting guard and a parasitic heal.
    tunnelVpnNetworkHandle?.let(ignoredVpnNetworkLossHandles::add)
    return RuntimeHandoverPreparation(
        ready = true,
        previousVpnNetworkHandle = tunnelVpnNetworkHandle,
        previousVpnInterfaceName = previousVpnIdentity.interfaceName,
    )
}

/** Profile tunnel → replacement profile tunnel, used by the fail-closed manual protocol test. */
internal suspend fun FoxholeVpnService.retireActiveTunnelForTunnelHandover(): RuntimeHandoverPreparation {
    val session = activeSession ?: return RuntimeHandoverPreparation(
        ready = true,
        previousVpnNetworkHandle = null,
        previousVpnInterfaceName = null,
    )
    val previousVpnIdentity = activeVpnNetworkIdentity()
    val tunnelVpnNetworkHandle = previousVpnIdentity.handle
    container.diagnosticsLogger.record(
        "connection",
        "profile tunnel handoff to replacement",
    )
    persistProfileTraffic(session, trafficSampler.sample())
    val prepared =
        closeRuntimeSession(
            reason = "profile_tunnel_handoff",
            interfaceDisposition = RuntimeInterfaceDisposition.RETIRE_FOR_HANDOVER,
        )
    if (!prepared) {
        return RuntimeHandoverPreparation(
            ready = false,
            previousVpnNetworkHandle = tunnelVpnNetworkHandle,
            previousVpnInterfaceName = previousVpnIdentity.interfaceName,
        )
    }
    tunnelVpnNetworkHandle?.let(ignoredVpnNetworkLossHandles::add)
    return RuntimeHandoverPreparation(
        ready = true,
        previousVpnNetworkHandle = tunnelVpnNetworkHandle,
        previousVpnInterfaceName = previousVpnIdentity.interfaceName,
    )
}

/**
 * Runs [establishNextInterface] while every retired runtime still owns its established interface,
 * and stops them only afterwards — the break half of make-before-break, and the one place the old
 * TUN is closed.
 *
 * The stop runs on every outcome, a failed or cancelled switch included, so a runtime can never be
 * left holding a TUN with no owner able to close it.
 */
internal suspend fun <T> FoxholeVpnService.establishNextInterfaceThenStopRetiredRuntimes(
    reason: String,
    establishNextInterface: suspend () -> T,
): T =
    runtimeInstanceStore.establishNextInterfaceThenStopRetired(
        stopRetired = { retired -> stopRetiredRuntime(retired, reason) },
        establishNextInterface = establishNextInterface,
    )

/** Same handover, but exposes the old-TUN close proof needed before publishing CONNECTED. */
internal suspend fun <T> FoxholeVpnService.establishNextInterfaceThenStopRetiredRuntimesWithProof(
    reason: String,
    establishNextInterface: suspend () -> T,
): RuntimeHandoverCompletion<T> {
    var retiredTunClosed = true
    val value =
        runtimeInstanceStore.establishNextInterfaceThenStopRetired(
            stopRetired = { retired ->
                retiredTunClosed = stopRetiredRuntime(retired, reason) && retiredTunClosed
            },
            establishNextInterface = establishNextInterface,
        )
    return RuntimeHandoverCompletion(value = value, retiredTunClosed = retiredTunClosed)
}

/**
 * Protocol tests keep a quiesced master TUN parked when a candidate cannot establish. The open,
 * non-processing interface freezes device traffic until the next candidate or the restore tunnel
 * succeeds; teardown still drains it through [stopRetiredRuntimes].
 */
internal suspend fun <T : Any> FoxholeVpnService.establishProtocolTestInterfaceThenStopRetiredOnSuccess(
    reason: String,
    establishNextInterface: suspend () -> T?,
): T? {
    val result = establishNextInterface()
    if (result != null) {
        stopRetiredRuntimes(reason)
    } else {
        container.diagnosticsLogger.record(
            "runtime",
            "protocol test candidate failed; retired interface remains fail-closed",
        )
    }
    return result
}

/** Drains the retirement park; a no-op when no handover is in flight. */
internal suspend fun FoxholeVpnService.stopRetiredRuntimes(reason: String) {
    runtimeInstanceStore.stopEveryRetiredRuntime { retired -> stopRetiredRuntime(retired, reason) }
}

private suspend fun FoxholeVpnService.stopRetiredRuntime(
    retired: FoxholeRuntime,
    reason: String,
): Boolean {
    val result = stopRuntimeFailClosed(reason = reason, target = retired)
    // A native close that times out can leave a managed pluggable transport behind. Reaped after
    // the stop, never before it: while the retired runtime still carries traffic its transports are
    // in use, not orphaned.
    val reaped = reapTorTransportOrphans()
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "retired runtime stopped after interface handover",
        "reason=$reason",
        "graceful=${result.graceful}",
        "tun_closed=${result.tunClosed}",
        "transport_orphans_reaped=$reaped",
    )
    return result.tunClosed
}

internal suspend fun FoxholeVpnService.stopRuntimeFailClosed(
    reason: String,
    policy: RuntimeStopPolicy? = null,
    /** The runtime to stop; defaults to the live one. Set only for a retired runtime. */
    target: FoxholeRuntime? = null,
): RuntimeStopResult {
    val currentRuntime = target ?: runtimeInstanceStore.current()
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
): Boolean {
    val handle = previousVpnNetworkHandle ?: return true
    // ConnectivityManager's handle is the acceptance boundary: FoxCore owns a dup of the
    // VpnService descriptor, so a closed Kotlin ParcelFileDescriptor proves nothing about the
    // NetworkAgent. That shortcut once left a validated tun0 with full-device routes installed
    // indefinitely after a rare Tor-only Stop. Wait for the actual old handle to go.
    if (awaitVpnNetworkTeardownGracefulSettle(handle, reason)) {
        return true
    }
    if (currentVpnNetworkOrNull()?.networkHandle != handle) {
        return true
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
        return true
    }
    return terminateProcessIfTunnelStillUp(handle, reason)
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
                container.diagnosticsLogger.recordFailure(
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
): Boolean {
    if (currentVpnNetworkOrNull()?.networkHandle != handle) {
        return true
    }
    val nativeSnapshot = runtimeInstanceStore.nativeSnapshot()
    val tunDescriptorProbe = processTunFileDescriptorProbe(nativeSnapshot.masterTunFd)
    // Live dev.60-opt repro: a fully clean native close with ConnectivityManager still reporting
    // the old VPN network for 8+ s. Killing there releases nothing (only NetworkAgent bookkeeping
    // lags) — it just crashes the app in the user's hands. The kill is reserved for a positively
    // observed foreign descriptor in THIS process. The native snapshot is diagnostic only: it can
    // remain stale after close, and treating it as ownership turns harmless framework lag into
    // SIGKILL.
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
        return false
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
    return false
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

internal fun shouldTerminateProcessForStuckTunnel(
    @Suppress("UNUSED_PARAMETER")
    nativeSnapshot: NativeRuntimeSnapshot,
    tunDescriptorProbe: ProcessTunDescriptorProbe,
): Boolean =
    tunDescriptorProbe == ProcessTunDescriptorProbe.OPEN

internal suspend fun FoxholeVpnService.stopRuntimeHelpersIfNeeded(
    reason: String,
    onTeardownPhase: ((RuntimeTeardownPhase) -> Unit)? = null,
) {
    // A native close that times out can still leave a managed pluggable transport behind. Reap
    // only the executable copied into this app's private native-library directory.
    onTeardownPhase?.invoke(RuntimeTeardownPhase.TOR)
    val reaped = reapTorTransportOrphans()
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "arti transport helpers stopped",
        "reason=$reason",
        "transport_orphans_reaped=$reaped",
    )
    onTeardownPhase?.invoke(RuntimeTeardownPhase.I2P)
    stopI2pdProcessIfNeeded(reason)
}

internal fun runtimeTeardownPhases(
    session: VpnSession?,
    previousSnapshot: ConnectionSnapshot,
    i2pPhase: I2pNetworkPhase,
    previousVpnNetworkHandle: Long?,
): Set<RuntimeTeardownPhase> =
    buildSet {
        if (session != null && session.profileId != TOR_ONLY_PROFILE_ID) add(RuntimeTeardownPhase.VPN)
        if (session?.torActive == true || session?.profileId == TOR_ONLY_PROFILE_ID || previousSnapshot.torActive) {
            add(RuntimeTeardownPhase.TOR)
        }
        if (i2pPhase != I2pNetworkPhase.OFFLINE) add(RuntimeTeardownPhase.I2P)
        if (previousVpnNetworkHandle != null) add(RuntimeTeardownPhase.ANDROID_TUNNEL)
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
