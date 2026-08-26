package com.foxhole.guard.runtime

import android.Manifest
import android.app.ActivityManager
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
import com.foxhole.core.runtime.FailClosedEvent
import com.foxhole.core.runtime.FoxholeRuntime
import com.foxhole.core.runtime.I2pTunnelTransition
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.NativeForceStopOutcome
import com.foxhole.core.runtime.NativeRuntimeSnapshot
import com.foxhole.core.runtime.RevokeOutcome
import com.foxhole.core.runtime.RuntimeChildProcessReaper
import com.foxhole.core.runtime.RuntimeResumeStateStore
import com.foxhole.core.runtime.RuntimeStopPolicy
import com.foxhole.core.runtime.RuntimeStopResult
import com.foxhole.core.runtime.applyKillSwitch
import com.foxhole.core.runtime.establishNextInterfaceThenStopRetired
import com.foxhole.core.runtime.nativeForceStopOutcomeOrNull
import com.foxhole.core.runtime.stopEveryRetiredRuntime
import com.foxhole.core.runtime.stopFailClosed
import com.foxhole.guard.R
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import com.foxhole.guard.ui.cli.CliMainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Runtime stop / fail-closed teardown helpers for [FoxholeVpnService]. */

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

    localGuardVpnNetworkHandle?.let(ignoredVpnNetworkLossHandles::add)
    container.diagnosticsLogger.record("runtime", "network activity logging suspended for vpn handoff validation")
    return RuntimeHandoverPreparation(
        ready = true,
        previousVpnNetworkHandle = localGuardVpnNetworkHandle,
        previousVpnInterfaceName = previousVpnIdentity.interfaceName,
    )
}

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

    tunnelVpnNetworkHandle?.let(ignoredVpnNetworkLossHandles::add)
    applyI2pCarrierPlan(I2pTunnelTransition.TUNNEL_STOPPED)
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

internal suspend fun <T> FoxholeVpnService.establishNextInterfaceThenStopRetiredRuntimes(
    reason: String,
    establishNextInterface: suspend () -> T,
): T =
    runtimeInstanceStore.establishNextInterfaceThenStopRetired(
        stopRetired = { retired -> stopRetiredRuntime(retired, reason) },
        establishNextInterface = establishNextInterface,
    )

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

internal suspend fun FoxholeVpnService.stopRetiredRuntimes(reason: String) {
    runtimeInstanceStore.stopEveryRetiredRuntime { retired -> stopRetiredRuntime(retired, reason) }
}

private suspend fun FoxholeVpnService.stopRetiredRuntime(
    retired: FoxholeRuntime,
    reason: String,
): Boolean {
    val result = stopRuntimeFailClosed(reason = reason, target = retired)
    val reapHelpers = shouldReapTorHelpersAfterRetiredStop(activeSession)
    val reaped = if (reapHelpers) reapTorTransportOrphans() else 0
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "retired runtime stopped after interface handover",
        "reason=$reason",
        "graceful=${result.graceful}",
        "tun_closed=${result.tunClosed}",
        "transport_orphans_reaped=$reaped",
        "transport_reap_deferred=${!reapHelpers}",
    )
    return result.tunClosed
}

internal fun shouldReapTorHelpersAfterRetiredStop(activeReplacement: VpnSession?): Boolean =
    activeReplacement?.torActive != true && activeReplacement?.profileId != TOR_ONLY_PROFILE_ID

internal suspend fun FoxholeVpnService.stopRuntimeFailClosed(
    reason: String,
    policy: RuntimeStopPolicy? = null,

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
    cutEveryFlowIfKillSwitchArmed(runtime = currentRuntime, event = FailClosedEvent.RUNTIME_STOPPING, reason = reason)
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
    terminateProcessIfNativeForceStopPoisoned(
        forceStopOutcome = result.forceStopOutcome,
        reason = reason,
    )
    return result
}

internal fun FoxholeVpnService.cutEveryFlowIfKillSwitchArmed(
    runtime: FoxholeRuntime,
    event: FailClosedEvent,
    reason: String,
) {
    val armed = container.settingsRepository.settings.value.expert.killSwitchEnabled
    val outcome = runtime.applyKillSwitch(armed = armed, event = event) ?: return
    container.diagnosticsLogger.recordStructured(
        "connection",
        "kill switch cut every flow",
        "event=${event.name.lowercase()}",
        "reason=$reason",
        "outcome=${(outcome as? RevokeOutcome.Revoked)?.flows?.toString() ?: outcome.toString()}",
    )
}

internal suspend fun FoxholeVpnService.terminateProcessIfNativeForceStopPoisoned(
    forceStopOutcome: NativeForceStopOutcome,
    reason: String,
) {
    val settleMs = nativeForceStopTerminationSettleMs(forceStopOutcome)
    if (settleMs == 0L || !NativeForceStopProcessTerminationGate.tryClaim(forceStopOutcome)) return
    withContext(NonCancellable + Dispatchers.IO) {
        delay(settleMs)
        terminateProcessIfTunnelStillUp(
            handle = currentVpnNetworkOrNull()?.networkHandle,
            reason = reason,
            forceStopOutcome = forceStopOutcome,
        )
    }
}

internal class NativeForceStopTerminationGate {
    private val claimed = AtomicBoolean(false)

    fun tryClaim(outcome: NativeForceStopOutcome): Boolean =
        outcome.processPoisoned && claimed.compareAndSet(false, true)
}

private object NativeForceStopProcessTerminationGate {
    private val gate = NativeForceStopTerminationGate()

    fun tryClaim(outcome: NativeForceStopOutcome): Boolean = gate.tryClaim(outcome)
}

internal suspend fun handleNativeForceStopPoison(
    result: Result<*>,
    onTerminate: suspend (NativeForceStopOutcome) -> Unit,
): Boolean {
    val outcome = result.nativeForceStopOutcomeOrNull() ?: return false
    onTerminate(outcome)
    return true
}

internal fun nativeForceStopTerminationSettleMs(outcome: NativeForceStopOutcome): Long =
    if (outcome.processPoisoned) NATIVE_FORCE_STOP_SETTLE_MS else 0L

@Suppress("ReturnCount")
internal suspend fun FoxholeVpnService.awaitStoppedVpnNetworkTeardown(
    previousVpnNetworkHandle: Long?,
    reason: String,
): Boolean {
    val handle = previousVpnNetworkHandle ?: return true

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
        val result =
            runCatching { runtime.forceKill("vpn_network_teardown_pending:$reason") }
                .onFailure {
                    container.diagnosticsLogger.recordFailure(
                        "connection",
                        "vpn network teardown force-kill failed: ${it.message.orEmpty()}",
                    )
                }.getOrNull()
        result?.let { kill ->
            terminateProcessIfNativeForceStopPoisoned(
                forceStopOutcome = kill.forceStopOutcome,
                reason = reason,
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

private fun FoxholeVpnService.terminateProcessIfTunnelStillUp(
    handle: Long?,
    reason: String,
    forceStopOutcome: NativeForceStopOutcome = NativeForceStopOutcome.NOT_ATTEMPTED,
): Boolean {
    if (!forceStopOutcome.processPoisoned && currentVpnNetworkOrNull()?.networkHandle != handle) {
        return true
    }
    val nativeSnapshot = runtimeInstanceStore.nativeSnapshot()
    val tunDescriptorProbe = processTunFileDescriptorProbe(nativeSnapshot.masterTunFd)

    if (!shouldTerminateProcessForStuckTunnel(nativeSnapshot, tunDescriptorProbe, forceStopOutcome)) {
        container.diagnosticsLogger.recordStructured(
            "connection",
            "vpn network teardown lagging without held tun fd; skipping process kill",
            "reason=$reason",
            "previous_handle=${handle ?: "none"}",
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
        if (forceStopOutcome.processPoisoned) {
            "native force stop poisoned process; terminating after bounded settle"
        } else {
            "vpn network teardown unresolved; terminating process to guarantee tunnel teardown"
        },
        "reason=$reason",
        "previous_handle=${handle ?: "none"}",
        "force_stop=${forceStopOutcome.name.lowercase()}",
        "tun_probe=${tunDescriptorProbe.name.lowercase()}",
        "native_engine=${nativeSnapshot.hasEngineHandle}",
        "native_tun=${nativeSnapshot.hasTunFileDescriptor}",
        "master_fd=${nativeSnapshot.masterTunFd ?: "none"}",
        "foreign_fd=${lastForeignTunDescriptor ?: "none"}",
    )
    setFailClosedProcessStateSummary()
    container.diagnosticsLogger.recordProcessTerminationTombstoneSync(
        "fail-closed process termination",
        "reason=$reason",
        "force_stop=${forceStopOutcome.name.lowercase()}",
        "tun_probe=${tunDescriptorProbe.name.lowercase()}",
        "native_engine=${nativeSnapshot.hasEngineHandle}",
        "native_tun=${nativeSnapshot.hasTunFileDescriptor}",
    )

    runCatching { postAbnormalTeardownNotification() }
    runCatching { removeForegroundNotification() }
    android.os.Process.killProcess(android.os.Process.myPid())
    return false
}

private fun FoxholeVpnService.setFailClosedProcessStateSummary() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        getSystemService(ActivityManager::class.java)
            ?.setProcessStateSummary(failClosedProcessStateSummaryBytes())
    }
}

internal fun failClosedProcessStateSummaryBytes(): ByteArray =
    FAIL_CLOSED_PROCESS_STATE_SUMMARY.toByteArray(Charsets.UTF_8)

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
internal const val NATIVE_FORCE_STOP_SETTLE_MS = 500L
private const val FAIL_CLOSED_PROCESS_STATE_SUMMARY = "foxhole_fail_closed_teardown"

internal enum class ProcessTunDescriptorProbe {
    OPEN,
    CLOSED,
    UNKNOWN,
}

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
    forceStopOutcome: NativeForceStopOutcome = NativeForceStopOutcome.NOT_ATTEMPTED,
): Boolean =
    forceStopOutcome.processPoisoned || tunDescriptorProbe == ProcessTunDescriptorProbe.OPEN

internal suspend fun FoxholeVpnService.stopRuntimeHelpersIfNeeded(
    reason: String,
    onTeardownPhase: ((RuntimeTeardownPhase) -> Unit)? = null,
) {
    onTeardownPhase?.invoke(RuntimeTeardownPhase.TOR)
    val reaped = reapTorTransportOrphans()
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "arti transport helpers stopped",
        "reason=$reason",
        "transport_orphans_reaped=$reaped",
    )
    onTeardownPhase?.invoke(RuntimeTeardownPhase.I2P)
    applyI2pCarrierPlan(I2pTunnelTransition.RUNTIME_STOPPING)
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

internal fun FoxholeVpnService.reapTorTransportOrphans(): Int {
    val executablePaths =
        TOR_TRANSPORT_LIBRARY_NAMES
            .mapTo(linkedSetOf()) { name -> java.io.File(applicationInfo.nativeLibraryDir, name).absolutePath }
    val dataDirectoryRoot = java.io.File(filesDir, ARTI_DATA_ROOT_DIR_NAME).absolutePath
    return RuntimeChildProcessReaper(
        selfPid = android.os.Process.myPid(),
        killProcess = { pid -> android.os.Process.killProcess(pid) },
        diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
    ).reapOrphans(executablePaths = executablePaths, dataDirectoryRoot = dataDirectoryRoot)
}

internal fun FoxholeVpnService.reapTorTransportOrphansAfterServiceDestroy(owner: String) {
    val reaped = reapTorTransportOrphans()
    container.diagnosticsLogger.recordStructured(
        "runtime",
        "arti transport cleanup after service destroy",
        "owner=$owner",
        "transport_orphans_reaped=$reaped",
    )
}

private val TOR_TRANSPORT_LIBRARY_NAMES = setOf("liblyrebird.so", "libconjure_client.so")
private const val ARTI_DATA_ROOT_DIR_NAME = "foxcore/arti"

internal suspend fun FoxholeVpnService.stopI2pdProcessIfNeeded(reason: String) {
    container.i2pdManager.stop()

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
