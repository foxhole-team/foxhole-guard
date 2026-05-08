package com.foxhole.beta.vpn

import com.foxhole.beta.R
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.Settings
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
    val delayMs = RuntimeAutoReconnectPolicy.backoffDelayMs(nextAttempt, retryDelaySeconds)
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
    val optionId = session.protocolOptionId?.trim()?.takeIf(String::isNotBlank) ?: return
    val profile = container.profileRepository.getProfile(session.profileId) ?: return
    val supportedOptionIds = MultiProtocolProfileSupport.supportedOptions(profile).map { option -> option.id }.toSet()
    if (optionId !in supportedOptionIds) {
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

private suspend fun FoxholeVpnService.reconnectActiveRuntime(
    session: VpnSession,
    reason: String,
    attempt: Int,
) {
    val previousVpnNetworkHandle = currentVpnNetworkOrNull()?.networkHandle
    stopActiveRuntimeForReconnect(session = session, reason = reason, attempt = attempt)
    connect(
        profileId = session.profileId,
        commandStartId = 0,
        protocolOptionIdOverride = session.protocolOptionId,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
    )
}

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
    stopActiveRuntimeForReconnect(session = session, reason = "smart_start_failover:$reason", attempt = exhaustedAttempts + 1)
    connect(
        profileId = session.profileId,
        commandStartId = 0,
        protocolOptionIdOverride = fallbackOptionId,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
    )
}

private suspend fun FoxholeVpnService.smartStartFailoverOptionId(
    session: VpnSession,
    settings: Settings,
): String? {
    val profile = container.profileRepository.getProfile(session.profileId) ?: return null
    val supportedOptions = MultiProtocolProfileSupport.supportedOptions(profile)
    if (supportedOptions.size < 2) {
        return null
    }
    val preference = settings.smartProfilePreference(profile.id)
    val excludedOptionIds = preference?.excludedProtocolOptionIds.orEmpty().toSet()
    val currentOptionId = session.protocolOptionId?.takeIf(String::isNotBlank) ?: profile.selectedProtocolOptionId
    val fallbackOptions =
        supportedOptions
            .filterNot { option -> option.id in excludedOptionIds }
            .filterNot { option -> option.id == currentOptionId }
    if (fallbackOptions.isEmpty()) {
        return null
    }
    val fallbackIds = fallbackOptions.map { option -> option.id }.toSet()
    val memoriesById = preference?.protocolMemories.orEmpty().associateBy { memory -> memory.optionId }
    val orderedIds =
        preference?.recommendedProtocolIds.orEmpty() +
            listOfNotNull(preference?.lastKnownGoodOptionId) +
            fallbackOptions
                .sortedWith(
                    compareBy(
                        { option -> memoriesById[option.id]?.lastLatencyMs ?: Long.MAX_VALUE },
                        { option -> option.id },
                    ),
                ).map { option -> option.id }
    return orderedIds.firstOrNull { optionId -> optionId in fallbackIds }
}

private const val SMART_START_FAILOVER_MIN_DOWN_MS = 60L * 1000L

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
    container.diagnosticsLogger.recordStructured(
        "connection",
        "runtime restarting",
        "reason=$reason",
        "attempt=$attempt",
        "sessionId=${session.correlationId}",
    )
    runtime.stop()
    activeSession = null
    container.connectionController.clearAppliedRuntime()
    FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.reset())
    FoxholeVpnRuntimeBridge.update(
        FoxholeVpnRuntimeBridge.snapshot.value.copy(
            state = ConnectionState.RECONNECTING,
            message =
                getString(
                    if (reason.startsWith("smart_start_failover:")) {
                        R.string.status_smart_start_reconnecting
                    } else {
                        R.string.status_reconnecting
                    },
                ),
        ),
    )
    updateNotification()
}
