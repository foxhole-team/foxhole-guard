package com.foxhole.beta.vpn

import com.foxhole.beta.R
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.VpnSession
import com.foxhole.beta.core.network.scopedByNetworkRules
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.core.settings.smartProfilePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.WeakHashMap

private data class VpnAutoReconnectState(
    var job: Job? = null,
    var attempts: Int = 0,
)

private val vpnAutoReconnectStates = WeakHashMap<FoxholeVpnService, VpnAutoReconnectState>()

internal fun FoxholeVpnService.scheduleAutoReconnect(reason: String) {
    val session = activeSession
    val reconnectState = autoReconnectState()
    if (reconnectState.job?.isActive == true || session == null || !defaultNetworkAvailable) {
        return
    }
    val nextAttempt = reconnectState.attempts + 1
    val connectionSettings = container.settingsRepository.settings.value.connection
    val autoReconnectEnabled = connectionSettings.autoReconnect
    val maxAttempts =
        if (connectionSettings.smartStartV2RayTunSubscriptionsEnabled) {
            connectionSettings.smartStartSubscriptionRetryAttempts
        } else {
            RuntimeAutoReconnectPolicy.MAX_ATTEMPTS
        }
    val retryDelaySeconds =
        connectionSettings.smartStartSubscriptionRetryDelaySeconds
            .takeIf { connectionSettings.smartStartV2RayTunSubscriptionsEnabled }
    if (RuntimeAutoReconnectPolicy.shouldSchedule(autoReconnectEnabled, nextAttempt, maxAttempts)) {
        scheduleAutoReconnectAttempt(reconnectState, session, reason, nextAttempt, retryDelaySeconds)
    } else if (autoReconnectEnabled && reconnectState.attempts == maxAttempts) {
        reconnectState.attempts += 1
        container.diagnosticsLogger.record(
            "connection",
            "auto reconnect exhausted reason=$reason attempts=$maxAttempts",
        )
        scheduleSmartStartFailoverAttempt(reconnectState, session, reason, maxAttempts)
    }
}

internal fun FoxholeVpnService.resetAutoReconnectState() {
    cancelScheduledAutoReconnect(resetAttempts = true)
}

internal fun FoxholeVpnService.cancelScheduledAutoReconnect(resetAttempts: Boolean) {
    val reconnectState = autoReconnectState()
    reconnectState.job?.cancel()
    reconnectState.job = null
    if (resetAttempts) {
        reconnectState.attempts = 0
    }
}

private fun FoxholeVpnService.scheduleAutoReconnectAttempt(
    reconnectState: VpnAutoReconnectState,
    session: VpnSession,
    reason: String,
    nextAttempt: Int,
    retryDelaySeconds: Int?,
) {
    reconnectState.attempts = nextAttempt
    val delayMs = RuntimeAutoReconnectPolicy.jitteredBackoffDelayMs(nextAttempt, retryDelaySeconds)
    RuntimeHealthMetrics.recordReconnectScheduled(
        owner = "vpn",
        attempt = nextAttempt,
        diagnosticsLogger = container.diagnosticsLogger,
    )
    container.diagnosticsLogger.recordStructured(
        "connection",
        "auto reconnect scheduled",
        "reason=$reason",
        "attempt=$nextAttempt",
        "delay_ms=$delayMs",
        "sessionId=${session.correlationId}",
    )
    reconnectState.job =
        scope.launch(Dispatchers.Default) {
            delay(delayMs)
            launchCommand {
                val current = activeSession
                if (current?.correlationId == session.correlationId) {
                    reconnectIfStillEnabled(session, reason, nextAttempt)
                }
            }
        }
}

private fun FoxholeVpnService.scheduleSmartStartFailoverAttempt(
    reconnectState: VpnAutoReconnectState,
    session: VpnSession,
    reason: String,
    exhaustedAttempts: Int,
) {
    reconnectState.job =
        scope.launch(Dispatchers.Default) {
            recordSmartStartProtocolDownAfterReconnectExhausted(session, reason, exhaustedAttempts)
            if (!container.settingsRepository.current().connection.smartStartFailoverEnabled) {
                container.diagnosticsLogger.record("connection", "smart start failover skipped because setting is disabled")
                return@launch
            }
            FoxholeVpnRuntimeBridge.update(
                FoxholeVpnRuntimeBridge.snapshot.value.copy(
                    state = ConnectionState.RECONNECTING,
                    message = getString(R.string.status_smart_start_reconnecting),
                ),
            )
            updateNotification()
            delay(SMART_START_FAILOVER_MIN_DOWN_MS)
            launchCommand {
                val current = activeSession
                if (current?.correlationId == session.correlationId) {
                    reconnectSmartStartFallbackIfStillEnabled(session, reason, exhaustedAttempts)
                }
            }
        }
}

private suspend fun FoxholeVpnService.recordSmartStartProtocolDownAfterReconnectExhausted(
    session: VpnSession,
    reason: String,
    exhaustedAttempts: Int,
) {
    recordSmartStartProtocolDown(
        session = session,
        reasonCode = smartStartReconnectReasonCode(reason),
        headline = "auto reconnect exhausted protocol marked down",
        detail = "reason=$reason attempts=$exhaustedAttempts",
    )
}

internal suspend fun FoxholeVpnService.recordSmartStartProtocolDown(
    session: VpnSession,
    reasonCode: AutoConnectReasonCode,
    headline: String,
    detail: String? = null,
) {
    val optionId = session.protocolOptionId?.trim()?.takeIf(String::isNotBlank)
    val profile = optionId?.let { container.profileRepository.getProfile(session.profileId) }
    val supportedOptionIds =
        profile
            ?.let(MultiProtocolProfileSupport::supportedOptions)
            .orEmpty()
            .map { option -> option.id }
            .toSet()
    if (optionId == null || optionId !in supportedOptionIds) {
        return
    }
    val recordedAt = System.currentTimeMillis()
    val settings = container.settingsRepository.current()
    val networkFingerprint =
        container.networkFingerprintProvider
            .currentFingerprint()
            ?.scopedByNetworkRules(settings.networkRules)
    container.settingsRepository.recordSmartProfileProbeResult(
        profileId = session.profileId,
        optionId = optionId,
        latencyMs = null,
        success = false,
        reasonCode = reasonCode,
        markAsLastKnownGood = false,
        networkFingerprint = networkFingerprint?.key,
        recordedAt = recordedAt,
        connectDurationMs = null,
        validatedAt = null,
        trafficObservedAt = null,
        countTowardOutcomeHistory = true,
    )
    container.diagnosticsLogger.recordStructured(
        "auto-connect",
        headline,
        "profile_id=${session.profileId}",
        "option=$optionId",
        "protocol=${session.protocolHint.name.lowercase()}",
        "outcome=down",
        "reason=${reasonCode.wireCode}",
        detail,
    )
}

private fun smartStartReconnectReasonCode(reason: String): AutoConnectReasonCode =
    when {
        reason.contains("validation", ignoreCase = true) ||
            reason.contains("health", ignoreCase = true) -> AutoConnectReasonCode.VALIDATION_TIMEOUT
        else -> AutoConnectReasonCode.CONNECT_ERROR
    }

private fun FoxholeVpnService.autoReconnectState(): VpnAutoReconnectState =
    synchronized(vpnAutoReconnectStates) {
        vpnAutoReconnectStates.getOrPut(this) { VpnAutoReconnectState() }
    }

private suspend fun FoxholeVpnService.reconnectIfStillEnabled(
    session: VpnSession,
    reason: String,
    attempt: Int,
) {
    if (!container.settingsRepository.current().connection.autoReconnect) {
        container.diagnosticsLogger.record("connection", "auto reconnect skipped because setting is disabled")
        return
    }
    reconnectActiveRuntime(session, reason, attempt)
}

@Suppress("TooGenericExceptionCaught")
private suspend fun FoxholeVpnService.reconnectActiveRuntime(
    session: VpnSession,
    reason: String,
    attempt: Int,
) {
    val previousVpnNetworkHandle = currentVpnNetworkOrNull()?.networkHandle
    try {
        stopActiveRuntimeForReconnect(session = session, reason = reason, attempt = attempt)
        connect(
            profileId = session.profileId,
            commandStartId = 0,
            protocolOptionIdOverride = session.protocolOptionId,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
        )
    } catch (error: Throwable) {
        clearDetachedReconnectSnapshot("auto reconnect failed: ${error.javaClass.simpleName}")
        throw error
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun FoxholeVpnService.reconnectSmartStartFallbackIfStillEnabled(
    session: VpnSession,
    reason: String,
    exhaustedAttempts: Int,
) {
    val settings = container.settingsRepository.current()
    if (!settings.connection.autoReconnect || !settings.connection.smartStartFailoverEnabled) {
        container.diagnosticsLogger.record("connection", "smart start failover skipped because setting is disabled")
        return
    }
    val fallbackOptionId = smartStartFailoverOptionId(session, settings)
    if (fallbackOptionId == null) {
        container.diagnosticsLogger.record(
            "connection",
            "smart start failover skipped reason=$reason attempts=$exhaustedAttempts fallback=none",
        )
        return
    }
    val previousVpnNetworkHandle = currentVpnNetworkOrNull()?.networkHandle
    try {
        stopActiveRuntimeForReconnect(
            session = session,
            reason = "smart_start_failover:$reason",
            attempt = exhaustedAttempts + 1,
        )
        connect(
            profileId = session.profileId,
            commandStartId = 0,
            protocolOptionIdOverride = fallbackOptionId,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
        )
    } catch (error: Throwable) {
        clearDetachedReconnectSnapshot("smart start failover reconnect failed: ${error.javaClass.simpleName}")
        throw error
    }
}

private suspend fun FoxholeVpnService.smartStartFailoverOptionId(
    session: VpnSession,
    settings: Settings,
): String? =
    container.profileRepository.getProfile(session.profileId)?.let { profile ->
        val supportedOptions = MultiProtocolProfileSupport.supportedOptions(profile)
        val preference = settings.smartProfilePreference(profile.id)
        val excludedOptionIds = preference?.excludedProtocolOptionIds.orEmpty().toSet()
        val currentOptionId = session.protocolOptionId?.takeIf(String::isNotBlank) ?: profile.selectedProtocolOptionId
        val fallbackOptions =
            supportedOptions
                .takeIf { options -> options.size >= 2 }
                .orEmpty()
                .filterNot { option -> option.id in excludedOptionIds }
                .filterNot { option -> option.id == currentOptionId }
        fallbackOptions.takeIf { it.isNotEmpty() }?.let { options ->
            val fallbackIds = options.map { option -> option.id }.toSet()
            val memoriesById = preference?.protocolMemories.orEmpty().associateBy { memory -> memory.optionId }
            val orderedIds =
                preference?.recommendedProtocolIds.orEmpty() +
                    listOfNotNull(preference?.lastKnownGoodOptionId) +
                    options
                        .sortedWith(
                            compareBy(
                                { option -> memoriesById[option.id]?.lastLatencyMs ?: Long.MAX_VALUE },
                                { option -> option.id },
                            ),
                        ).map { option -> option.id }
            orderedIds.firstOrNull { optionId -> optionId in fallbackIds }
        }
    }

private const val SMART_START_FAILOVER_MIN_DOWN_MS = 3_000L

internal suspend fun FoxholeVpnService.persistProfileTrafficInternal(
    session: VpnSession,
    traffic: TrafficSnapshot,
) {
    if (!traffic.available && traffic.rxTotalBytes <= 0L && traffic.txTotalBytes <= 0L) {
        return
    }
    container.settingsRepository.accumulateProfileTraffic(
        profileId = session.profileId,
        profileName = session.profileName,
        protocolHint = session.protocolHint,
        protocolOptionId = session.protocolOptionId,
        transport = session.runtimeTransportProtocol(),
        rxBytes = traffic.rxTotalBytes,
        txBytes = traffic.txTotalBytes,
        updatedAt = System.currentTimeMillis(),
    )
}

private suspend fun FoxholeVpnService.stopActiveRuntimeForReconnect(
    session: VpnSession,
    reason: String,
    attempt: Int,
) {
    persistProfileTraffic(session, trafficSampler.sample())
    stopTrafficUpdates()
    stopGeoRefresh()
    stopNotificationHealthMonitoring()
    validationJob?.cancel()
    validationJob = null
    val messageRes =
        if (reason.startsWith("smart_start_failover:")) {
            R.string.status_smart_start_reconnecting
        } else {
            R.string.status_reconnecting
        }
    container.diagnosticsLogger.recordStructured(
        "connection",
        "runtime restarting",
        "reason=$reason",
        "attempt=$attempt",
        "sessionId=${session.correlationId}",
    )
    runtime.stop()
    activeSession = null
    activeVpnNetworkHandle = null
    container.connectionController.clearAppliedRuntime()
    FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.reset())
    FoxholeVpnRuntimeBridge.update(
        FoxholeVpnRuntimeBridge.snapshot.value.copy(
            state = ConnectionState.RECONNECTING,
            message = getString(messageRes),
        ),
    )
    updateNotification()
}

private fun FoxholeVpnService.clearDetachedReconnectSnapshot(reason: String) {
    val currentSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (activeSession != null || currentSnapshot.state != ConnectionState.RECONNECTING) {
        return
    }
    container.diagnosticsLogger.record("connection", "clearing detached vpn reconnect snapshot: $reason")
    FoxholeVpnRuntimeBridge.clearTransientState()
    FoxholeVpnRuntimeBridge.update(
        ConnectionSnapshot(
            trafficMode = container.settingsRepository.settings.value.traffic.mode,
        ),
    )
    removeForegroundNotification()
}
