package com.foxhole.guard.runtime

import android.net.Network
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.isDistinctTorRouteExit
import com.foxhole.core.model.withDnsServers
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.PrivateDnsSettings
import com.foxhole.core.runtime.RuntimeResumeStateStore
import com.foxhole.core.runtime.TunnelConnectivityProbe
import com.foxhole.core.runtime.TunnelValidationEvidence
import com.foxhole.core.runtime.TunnelValidationEvidenceClassifier
import com.foxhole.core.runtime.TunnelValidationGracePolicy
import com.foxhole.core.runtime.TunnelValidationPolicyContext
import com.foxhole.core.runtime.TunnelValidationProbeKind
import com.foxhole.core.runtime.VpnDnsServerSelector
import com.foxhole.core.runtime.acceptsTunnelValidationProbe
import com.foxhole.core.runtime.acceptsValidatedVpnLiteralIpEndpointProbe
import com.foxhole.core.runtime.activeTunnelIpRefreshEndpoint
import com.foxhole.core.runtime.dnsServerAddresses
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.core.runtime.requiresStrictRuntimeProxyIpRefresh
import com.foxhole.core.runtime.shouldPreferIpv4TunnelValidation
import com.foxhole.core.runtime.shouldPreferVpnBoundIpRefresh
import com.foxhole.core.runtime.shouldPublishRuntimeProxyIpInfoToDashboard
import com.foxhole.core.runtime.tunnelRuntimeProxyAccess
import com.foxhole.core.runtime.tunnelValidationPolicyContextFor
import com.foxhole.core.runtime.tunnelValidationRequestNetwork
import com.foxhole.guard.R
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun FoxholeVpnService.scheduleValidationInternal(
    session: VpnSession,
    failOnFailure: Boolean,
    expectedFreshVpnNetworkHandle: Long? = null,
    expectedFreshVpnInterfaceName: String? = null,
    onSuccess: (Network) -> Unit,
) {
    val validationEpoch = beginValidationEpoch("schedule:${session.correlationId}")
    val job =
        scope.launch(Dispatchers.Main.immediate) {
            if (!isCurrentValidationEpoch(validationEpoch) || !activeSession.matchesRuntimeValidationSession(session)) {
                container.diagnosticsLogger.record(
                    "dns",
                    "post-start probe ignored for stale session sessionId=${session.correlationId}",
                )
                return@launch
            }
            val validation = validateTunnelConnectivity(
                expectedFreshVpnNetworkHandle,
                expectedFreshVpnInterfaceName,
                session,
            )
            if (!isCurrentValidationEpoch(validationEpoch) || !activeSession.matchesRuntimeValidationSession(session)) {
                container.diagnosticsLogger.record(
                    "dns",
                    "post-start probe ignored for stale session sessionId=${session.correlationId}",
                )
                return@launch
            }
            if (validation.isSuccess) {
                container.diagnosticsLogger.record("dns", "post-start probe passed")
                onSuccess(validation.getOrThrow())
            } else {
                val error = validation.exceptionOrNull()
                val message = userFacingErrorMessage(error, R.string.error_dns_probe_failed)
                container.diagnosticsLogger.recordFailure("dns", "post-start probe failed: $message")
                if (failOnFailure) {
                    fail(
                        message = message,
                        reasonCode = runtimeValidationFailureReasonCode(
                            error = error,
                            dnsProbeFailedMessage = getString(R.string.error_dns_probe_failed),
                        ),
                    )
                } else {
                    scheduleAutoReconnect(reason = "post_network_validation_failed")
                }
            }
        }
    validationJob = job
}

internal suspend fun FoxholeVpnService.validateTunnelConnectivityInternal(
    expectedFreshVpnNetworkHandle: Long? = null,
    expectedFreshVpnInterfaceName: String? = null,
    session: VpnSession? = null,
): Result<Network> =
    withContext(Dispatchers.IO) {
        val validationStartedAt = System.currentTimeMillis()
        val currentSession = session ?: activeSession
        if (!canPublishValidationResult(currentSession)) {
            container.diagnosticsLogger.record(
                "dns",
                "runtime validation skipped for stale session sessionId=${currentSession?.correlationId.orEmpty()}",
            )
            return@withContext Result.failure(IllegalStateException("stale runtime validation session"))
        }
        val validationRun =
            prepareRuntimeValidationRun(
                validationStartedAt = validationStartedAt,
                currentSession = currentSession,
            )
        recordRuntimeValidationStarted(validationRun)
        val result =
            runTunnelConnectivityProbe(
                validationRun = validationRun,
                expectedFreshVpnNetworkHandle = expectedFreshVpnNetworkHandle,
                expectedFreshVpnInterfaceName = expectedFreshVpnInterfaceName,
            )
        if (!canPublishValidationResult(currentSession)) {
            container.diagnosticsLogger.record(
                "dns",
                "runtime validation result ignored for stale session sessionId=${currentSession?.correlationId.orEmpty()}",
            )
            return@withContext Result.failure(IllegalStateException("stale runtime validation session"))
        }
        finishRuntimeValidation(validationRun, result)
    }

@Suppress("ReturnCount")
internal suspend fun FoxholeVpnService.tryAcceptEarlyValidatedVpnLiteralEndpoint(
    vpnNetwork: Network,
    requestNetwork: Network?,
    validationStartedAt: Long,
    context: TunnelValidationPolicyContext,
    session: VpnSession? = null,
): Boolean {
    // The policy carries no rule for VALIDATED_VPN_LITERAL_IP_ENDPOINT, so `accepts` falls through
    // to its fail-closed `?: false` and the predicate below can never become true. Without this
    // guard every UDP-profile validation spent the whole early window polling a condition that
    // structurally could not fire — 900 ms of latency on connect, bought for nothing. The check is
    // deliberately a policy question rather than a hard `return false`: add a rule for the kind and
    // the fast path comes back to life on its own.
    if (!acceptsTunnelValidationProbe(TunnelValidationProbeKind.VALIDATED_VPN_LITERAL_IP_ENDPOINT, context)) {
        return false
    }
    val evidence =
        withTimeoutOrNull(FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_EARLY_WINDOW_MS) {
            while (currentCoroutineContext().isActive) {
                val currentEvidence = inspectValidatedTunnelEvidence(validationStartedAt)
                if (
                    acceptsValidatedVpnLiteralIpEndpointProbe(
                        androidValidated = isVpnNetworkValidated(vpnNetwork),
                        evidence = currentEvidence,
                        context = context,
                    )
                ) {
                    return@withTimeoutOrNull currentEvidence
                }
                delay(FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_POLL_MS)
            }
            null
        } ?: return false

    val literalProbe =
        runRuntimeValidationCatchingUnlessCancelled {
            probeDnsIndependentConnectivityFallback(
                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS,
                network = requestNetwork,
            )
        }
    if (literalProbe.isFailure) {
        container.diagnosticsLogger.recordFailure(
            "dns",
            "early vpn-bound literal public endpoint probe failed: ${literalProbe.exceptionOrNull()?.message.orEmpty()}",
        )
        return false
    }
    if (!acceptsValidatedVpnLiteralIpEndpointProbe(androidValidated = true, evidence = evidence, context = context)) {
        container.diagnosticsLogger.record(
            "dns",
            "early vpn-bound literal public endpoint probe passed but is not accepted as tunnel validation",
        )
        return false
    }
    container.diagnosticsLogger.record(
        "dns",
        "vpn-bound literal public endpoint accepted after android validation",
    )
    scope.launch(Dispatchers.IO) {
        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork, session)
    }
    return true
}

@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> runRuntimeValidationCatchingUnlessCancelled(
    crossinline block: suspend () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

@Suppress("ReturnCount")
internal suspend inline fun <T> Result<T>.recoverRuntimeValidationCatchingUnlessCancelled(
    crossinline transform: suspend (Throwable) -> T,
): Result<T> {
    if (isSuccess) {
        return this
    }
    val failure = exceptionOrNull() ?: return this
    return runRuntimeValidationCatchingUnlessCancelled { transform(failure) }
}

internal suspend fun FoxholeVpnService.retryValidatedTunnelConnectivityWithGraceInternal(
    vpnNetwork: Network,
    policy: TunnelValidationGracePolicy,
    preferIpv4: Boolean = false,
    session: VpnSession? = null,
): Result<Unit> {
    val validationPolicyContext = tunnelValidationPolicyContextFor(PrivateDnsSettings.current(this))
    return TunnelConnectivityProbe.run(
        attempts = policy.attempts,
        initialDelayMs = policy.initialDelayMs,
        retryDelayMs = policy.retryDelayMs,
        timeoutMs = policy.totalTimeoutMs,
        onFailure = { attemptIndex, error ->
            container.diagnosticsLogger.recordFailure(
                "dns",
                "grace retry ${attemptIndex + 1}/${policy.attempts} failed: ${error.message.orEmpty()}",
            )
        },
    ) {
        val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
        val resolverNetwork = currentUpstreamNetworkOrNull()
        val ipRefresh =
            runRuntimeValidationCatchingUnlessCancelled {
                refreshVpnIpInfo(
                    callTimeoutMs = policy.callTimeoutMs,
                    network = requestNetwork,
                )
            }
        if (ipRefresh.isSuccess) {
            ipRefresh.getOrThrow()
            return@run Unit
        }
        val endpointProbe =
            runRuntimeValidationCatchingUnlessCancelled {
                probeConnectivityEndpoints(
                    callTimeoutMs = policy.callTimeoutMs,
                    network = requestNetwork,
                    resolverNetwork = resolverNetwork,
                    preferIpv4 = preferIpv4,
                )
            }
        if (endpointProbe.isFailure) {
            if (
                tryAcceptGraceDnsIndependentFallback(
                    vpnNetwork = vpnNetwork,
                    requestNetwork = requestNetwork,
                    policy = policy,
                    validationPolicyContext = validationPolicyContext,
                    session = session,
                )
            ) {
                return@run Unit
            }
            throw (endpointProbe.exceptionOrNull() ?: IllegalStateException("connectivity probe failed"))
        }
        endpointProbe.getOrThrow()
        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork, session)
    }
}

private suspend fun FoxholeVpnService.tryAcceptGraceDnsIndependentFallback(
    vpnNetwork: Network,
    requestNetwork: Network?,
    policy: TunnelValidationGracePolicy,
    validationPolicyContext: TunnelValidationPolicyContext,
    session: VpnSession? = null,
): Boolean {
    val dnsIndependentFallback =
        runRuntimeValidationCatchingUnlessCancelled {
            probeDnsIndependentConnectivityFallback(
                callTimeoutMs = policy.callTimeoutMs,
                network = requestNetwork,
            )
        }
    return when {
        dnsIndependentFallback.isFailure -> {
            container.diagnosticsLogger.recordFailure(
                "dns",
                "grace retry dns-independent probe failed: ${dnsIndependentFallback.exceptionOrNull()?.message.orEmpty()}",
            )
            false
        }
        !acceptsTunnelValidationProbe(
            TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
            validationPolicyContext,
        ) -> {
            container.diagnosticsLogger.record(
                "dns",
                "grace retry dns-independent probe passed but is not accepted as tunnel validation",
            )
            false
        }
        else -> {
            container.diagnosticsLogger.record(
                "dns",
                "grace retry dns-independent probe accepted for strict private dns",
            )
            refreshValidatedTunnelIpInfoBestEffort(vpnNetwork, session)
            true
        }
    }
}

internal fun FoxholeVpnService.inspectValidatedTunnelEvidenceInternal(validationStartedAt: Long): TunnelValidationEvidence? {
    val evidence =
        TunnelValidationEvidenceClassifier.classify(
            entries = container.diagnosticsLogger.entries.value,
            sinceMs = validationStartedAt,
        )
    evidence.fatalRuntimeMessage?.let { fatalMessage ->
        container.diagnosticsLogger.recordFailure(
            "dns",
            "validated tunnel evidence rejected due to runtime error: $fatalMessage",
        )
        return null
    }
    return evidence.takeIf(TunnelValidationEvidence::hasSuccessfulTunnelActivity)
}

internal suspend fun FoxholeVpnService.refreshValidatedTunnelIpInfoBestEffortInternal(
    vpnNetwork: Network,
    session: VpnSession? = null,
) {
    val sessionSnapshot = session ?: activeSession
    if (!canPublishValidationResult(sessionSnapshot)) {
        container.diagnosticsLogger.record(
            "ip",
            "validated tunnel ip refresh ignored for stale session sessionId=${sessionSnapshot?.correlationId.orEmpty()}",
        )
        return
    }
    runRuntimeValidationCatchingUnlessCancelled {
        val settings = container.settingsRepository.current()
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        val androidValidatedVpnNetwork = isVpnNetworkValidated(vpnNetwork)
        val endpoint = activeTunnelIpRefreshEndpoint(
            configuredEndpoint = settings.connection.ipInfoEndpoint,
            androidValidatedVpnNetwork = androidValidatedVpnNetwork,
        )
        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(sessionSnapshot?.configJson)
        val preferIpv4Validation =
            shouldPreferIpv4TunnelValidation(sessionSnapshot?.protocolHint, sessionSnapshot?.configJson)
        val info = if (settings.shouldPreferVpnBoundIpRefresh(snapshot, androidValidatedVpnNetwork)) {
            val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
            val resolverNetwork = currentUpstreamNetworkOrNull()
            if (preferIpv4Validation) {
                container.ipInfoRepository.fetchIpv4(
                    endpoint = endpoint,
                    callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                    network = requestNetwork,
                    resolverNetwork = resolverNetwork,
                ) ?: error("vpn ipv4 refresh failed")
            } else {
                container.ipInfoRepository.fetch(
                    endpoint = endpoint,
                    callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                    network = requestNetwork,
                    resolverNetwork = resolverNetwork,
                    mode = IpInfoFetchMode.ENTRY_QUICK,
                )
            }
        } else if (preferIpv4Validation) {
            container.ipInfoRepository.fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                proxy = settings.tunnelRuntimeProxyAccess(),
                resolverNetwork = currentUpstreamNetworkOrNull(),
            ) ?: error("runtime proxy ipv4 refresh failed")
        } else {
            container.ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                proxy = settings.tunnelRuntimeProxyAccess(),
                resolverNetwork = currentUpstreamNetworkOrNull(),
                mode = IpInfoFetchMode.ENTRY_QUICK,
            )
        }
        info.withDnsServers(
            localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
            remoteDnsServers = remoteDnsServers,
        )
    }.recoverRuntimeValidationCatchingUnlessCancelled { primaryError ->
        val settings = container.settingsRepository.current()
        if (
            settings.requiresStrictRuntimeProxyIpRefresh(FoxholeVpnRuntimeBridge.snapshot.value) &&
            !isVpnNetworkValidated(vpnNetwork)
        ) {
            throw primaryError
        }
        val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
        val resolverNetwork = currentUpstreamNetworkOrNull()
        val endpoint = settings.connection.ipInfoEndpoint
        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(sessionSnapshot?.configJson)
        val ipv4Info =
            container.ipInfoRepository.fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
            ) ?: throw primaryError
        ipv4Info.withDnsServers(
            localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
            remoteDnsServers = remoteDnsServers,
        )
    }
        .onSuccess { info ->
            if (canPublishValidationResult(sessionSnapshot)) {
                val settings = container.settingsRepository.current()
                val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
                if (settings.shouldPublishRuntimeProxyIpInfoToDashboard(snapshot)) {
                    bridgeWriter.updateIpInfo(info)
                    container.diagnosticsLogger.record("ip", "validated tunnel ip refresh published to dashboard")
                } else if (info.isDistinctTorRouteExit(FoxholeVpnRuntimeBridge.ipInfo.value)) {
                    // Held out of the VPN dashboard IP because Tor is over the VPN: this validated
                    // exit (probed through tunnel -> runtime -> Tor) is the real Tor exit, so route it
                    // to the dedicated Tor channel for the map instead of dropping it.
                    bridgeWriter.updateTorRouteIpInfo(info)
                    container.diagnosticsLogger.record(
                        "ip",
                        "tor route exit published from validated tunnel ip while tor route is inside vpn",
                    )
                } else {
                    container.diagnosticsLogger.record(
                        "ip",
                        "validated tunnel ip refresh kept out of dashboard while tor route is inside vpn",
                    )
                }
            } else {
                container.diagnosticsLogger.record(
                    "ip",
                    "validated tunnel ip refresh ignored for stale session sessionId=${sessionSnapshot?.correlationId.orEmpty()}",
                )
            }
        }
        .onFailure { error ->
            container.diagnosticsLogger.recordFailure(
                "ip",
                "validated tunnel ip refresh deferred: ${error.message.orEmpty()}",
            )
        }
}

internal fun FoxholeVpnService.onConnectionStartedInternal(
    session: VpnSession,
    trafficMode: TrafficMode,
    refreshLastChangeAt: Boolean = true,
) {
    val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
    val analysisStatus = getString(R.string.notification_status_analysis)
    resetAutoReconnectState()
    RuntimeResumeStateStore.markProfileRuntime(this, trafficMode, session)
    runtimeNetworkActivityLoggingSuspended = false
    if (!sessionTicker.isRegistered(FoxholeVpnService.TICKER_TASK_TRAFFIC)) {
        trafficSampler.start()
        startTrafficUpdates()
    }
    startAppTrafficStatsUpdates()
    bridgeWriter.update(
        ConnectionSnapshot(
            state = ConnectionState.CONNECTED,
            trafficMode = trafficMode,
            profileId = session.profileId,
            profileName = session.profileName,
            protocolHint = session.protocolHint,
            protocolOptionId = session.protocolOptionId,
            torActive = session.torActive,
            message = previousSnapshot.message
                .takeIf { previousSnapshot.isSmartStartConnection && it == analysisStatus },
            isSmartStartConnection = previousSnapshot.isSmartStartConnection,
            // The accepted IP belongs to this transition generation. Preserve its boundary when
            // validation commits the session, otherwise the just-published identity is instantly
            // classified as stale against a newer timestamp.
            lastChangeAt = previousSnapshot.lastChangeAt,
        ),
        refreshLastChangeAt = refreshLastChangeAt,
    )
    torProbeOwnerEnabled = session.torActive
    scope.launch(Dispatchers.IO) { syncTorProbeProxy() }
    updateNotification()
    startGeoRefresh()
}

internal fun FoxholeVpnService.onTunnelValidatedInternal(
    session: VpnSession,
    vpnNetwork: Network,
) {
    if (!activeSession.matchesRuntimeValidationSession(session)) {
        container.diagnosticsLogger.record(
            "dns",
            "validated tunnel ignored for stale session sessionId=${session.correlationId}",
        )
        return
    }
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (snapshot.state !in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)) {
        container.diagnosticsLogger.record(
            "dns",
            "validated tunnel ignored for inactive state sessionId=${session.correlationId} state=${snapshot.state.name.lowercase()}",
        )
        return
    }
    activeVpnNetworkHandle = vpnNetwork.networkHandle
    registerVpnNetworkCallbackIfNeeded()
    if (snapshot.state != ConnectionState.CONNECTED || snapshot.inPlaceRuntimeReload) {
        // A validated hot replacement is still CONNECTED by design. Commit its session and clear
        // the in-place marker; otherwise the old session-shaped snapshot survives indefinitely.
        // Validation has just published identity stamped after the original CONNECTING/hot-apply
        // boundary. Preserve that boundary or the dashboard immediately rejects the new IpInfo as
        // stale by a few milliseconds.
        onConnectionStarted(session, TrafficMode.TUNNEL, refreshLastChangeAt = false)
    }
    startI2pdReadinessProbe(
        settings = container.settingsRepository.settings.value,
        session = session,
        vpnNetwork = vpnNetwork,
    )
    startGeoRefresh()
    // VPN-first Tor ordering: a session that deferred its in-tunnel Tor route engages it now,
    // after the tunnel has proven itself (and the network widget got its VPN identity window).
    scheduleDeferredTorRouteUpgrade(session)
}

internal fun VpnSession?.matchesRuntimeValidationSession(session: VpnSession): Boolean =
    this != null &&
        profileId == session.profileId &&
        correlationId == session.correlationId &&
        protocolOptionId == session.protocolOptionId

internal fun FoxholeVpnService.canPublishValidationResult(session: VpnSession?): Boolean =
    session != null && activeSession.matchesRuntimeValidationSession(session)
