package com.foxhole.guard.ui

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.reconcileActiveVpnNetworkIfNeeded
import com.foxhole.guard.ui.HomeViewModel.Companion.APP_FOREGROUND_REFRESH_MIN_INTERVAL_MS
import com.foxhole.guard.ui.HomeViewModel.Companion.FIRST_FOREGROUND_REFRESH_STARTUP_DELAY_MS
import com.foxhole.guard.ui.HomeViewModel.Companion.PROFILE_PRELOAD_TIMEOUT_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// The HomeViewModel init-block supervisors: each long-lived viewModelScope collector the
// dashboard needs from cold start lives here, so the ViewModel itself only wires them up.

/** Warm the secure settings store, then seed usage-access + sampler + local-guard state. */
internal fun HomeViewModel.startSettingsWarmupSupervision() {
    viewModelScope.launch {
        val settingsResult = runCatching {
            withContext(Dispatchers.IO) { container.settingsRepository.warmUp() }
        }
        settingsResult
            .onFailure {
                snackbars.emit(errorBanner(R.string.settings_secure_storage_failed))
            }
        val settings = settingsResult.getOrNull() ?: container.settingsRepository.settings.value
        appTrafficUsageAccessGrantedMutable.value =
            withContext(Dispatchers.IO) { appTrafficStatsRecorder.hasUsageAccess() }
        syncAppTrafficStatsSampler(appTrafficStatsRuntimeAllowed(settings))
        syncLocalGuardWithPermissionRequest()
    }
}

/** Keep the app-traffic sampler in sync with the statistics settings for the process lifetime. */
internal fun HomeViewModel.startAppTrafficSamplerSettingsSync() {
    viewModelScope.launch {
        container.settingsRepository.settings.collect { settings ->
            val enabled = appTrafficStatsRuntimeAllowed(settings)
            syncAppTrafficStatsSampler(enabled)
        }
    }
}

/**
 * Preload the active profile ahead of the first dashboard frame; any storage failure falls back
 * to the cached last-active profile so the UI never blocks on Room/crypto errors.
 */
internal fun HomeViewModel.startStartupProfilePreload() {
    viewModelScope.launch {
        // PIN-locked cold start: the SQLCipher key is not installed yet. Touching the
        // profile DB now throws DatabaseKeyUnavailableException on this worker and
        // crashes the process — wait for the unlock first (the UnlockScreen is up).
        awaitDatabaseUnlocked()
        val loaded =
            try {
                withTimeoutOrNull(PROFILE_PRELOAD_TIMEOUT_MS) {
                    val activeProfile =
                        withContext(Dispatchers.IO) {
                            container.profileRepository.ensureActiveProfileInvariant()
                            val profile = container.profileRepository.getActiveProfile()
                            container.profileRepository.profiles.first()
                            profile
                        }
                    startupActiveProfileMutable.value = activeProfile
                    true
                } == true
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: com.foxhole.guard.core.security.DatabaseKeyUnavailableException) {
                container.diagnosticsLogger.recordFailure(
                    "profile",
                    "profile preload skipped: ${error.message ?: "db locked"}"
                )
                false
            } catch (error: android.database.SQLException) {
                container.diagnosticsLogger.recordFailure(
                    "profile",
                    "profile preload failed: ${error::class.simpleName}",
                )
                false
            } catch (error: kotlinx.serialization.SerializationException) {
                container.diagnosticsLogger.recordFailure(
                    "profile",
                    "profile preload failed: ${error::class.simpleName}",
                )
                false
            } catch (error: java.io.IOException) {
                container.diagnosticsLogger.recordFailure(
                    "profile",
                    "profile preload failed: ${error::class.simpleName}",
                )
                false
            } catch (error: java.security.GeneralSecurityException) {
                container.diagnosticsLogger.recordFailure(
                    "profile",
                    "profile preload failed: ${error::class.simpleName}",
                )
                false
            } catch (error: IllegalStateException) {
                container.diagnosticsLogger.recordFailure(
                    "profile",
                    "profile preload failed: ${error::class.simpleName}",
                )
                false
            } catch (error: SecurityException) {
                container.diagnosticsLogger.recordFailure(
                    "profile",
                    "profile preload failed: ${error::class.simpleName}",
                )
                false
            }
        if (!loaded) {
            startupActiveProfileMutable.value =
                container.settingsRepository.settings.value.lastActiveProfile?.toStartupProfile()
            container.diagnosticsLogger.record("profile", "profile preload finished with fallback")
        }
        profilesLoadedMutable.value = true
    }
}

/** Mirror repository active-profile changes into the startup-profile stream. */
internal fun HomeViewModel.startActiveProfileStartupSync() {
    viewModelScope.launch {
        // Same PIN-lock gate as the preload: the activeProfile flow opens the DB.
        awaitDatabaseUnlocked()
        container.profileRepository.activeProfile.collect { activeProfile ->
            startupActiveProfileMutable.value = activeProfile
        }
    }
}

/**
 * Suspends until the encrypted profile DB is usable: with PASSWORD protection the
 * key rides the session that the unlock installs, so any DB-touching startup must
 * wait for it. No protection (or already unlocked) returns at once.
 */
internal suspend fun HomeViewModel.awaitDatabaseUnlocked() {
    if (securityComponents.isDatabaseLockedForBackground()) {
        securityComponents.dataKeyAvailable.first { available -> available }
    }
}

/**
 * The connection-snapshot reactor: clears per-connection UI state on terminal states and drives
 * the post-connect / network-change / post-disconnect IP refreshes.
 */
@Suppress("CyclomaticComplexMethod")
internal fun HomeViewModel.startConnectionSnapshotSupervision() {
    viewModelScope.launch {
        var previousState: ConnectionState? = null
        var previousUpstreamNetworkRevision: Long? = null
        var pendingUpstreamNetworkRevision: Long? = null
        container.connectionController.snapshot.collect { snapshot ->
            val currentState = snapshot.state
            val currentUpstreamNetworkRevision = snapshot.upstreamNetworkRevision
            val previousRevision = previousUpstreamNetworkRevision
            val upstreamNetworkRevisionAdvanced =
                previousRevision != null &&
                    currentUpstreamNetworkRevision > previousRevision
            if (upstreamNetworkRevisionAdvanced && currentState != ConnectionState.CONNECTED) {
                pendingUpstreamNetworkRevision = currentUpstreamNetworkRevision
            }
            val shouldRefreshConnectedIp =
                shouldAutoRefreshIpAfterConnect(
                    previousState = previousState,
                    currentState = currentState,
                )
            val shouldRefreshNetworkChangedIp =
                shouldAutoRefreshIpAfterUpstreamNetworkChange(
                    connectionState = currentState,
                    previousRevision = previousRevision,
                    currentRevision = currentUpstreamNetworkRevision,
                ) || shouldAutoRefreshIpAfterPendingUpstreamNetworkChange(
                    connectionState = currentState,
                    pendingRevision = pendingUpstreamNetworkRevision,
                    currentRevision = currentUpstreamNetworkRevision,
                )
            val shouldRefreshIdleIp =
                shouldAutoRefreshIpAfterDisconnect(
                    previousState = previousState,
                    currentState = currentState,
                )
            previousState = currentState
            previousUpstreamNetworkRevision = currentUpstreamNetworkRevision
            if (currentState !in ACTIVE_CONNECTION_STATES) {
                invalidateIpInfoRefreshes()
                cancelTorIdentityProbe()
                clearRuntimeReloadPending()
                clearRuntimeReconnectRequired()
                clearTorOperation()
                // Drop the last TOR exit identity on any terminal state, or a VPN+TOR stop leaves the
                // old TOR IP on screen (the runtime-side clear alone doesn't repaint when the
                // post-disconnect IP refresh times out).
                torIpInfoMutable.value = null
                pendingUpstreamNetworkRevision = null
                postConnectTorRouteRefreshJob?.cancel()
                postConnectTorRouteRefreshJob = null
                clearProfileLatencyRefresh()
                clearProtocolLatencyState()
            }
            if (shouldRefreshNetworkChangedIp && !autoConnectUiStateMutable.value.running) {
                pendingUpstreamNetworkRevision = null
                // Runtime-owned underlying change: invalidate the physical identity immediately;
                // the connected VPN exit itself remains valid for this unchanged route session.
                clearNetworkHandoverIpIdentity(clearDashboardIdentity = false)
                torIpInfoMutable.value = null
                if (snapshot.torActive || snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
                    torIdentityProbeTimeoutJob?.cancel()
                    torIdentityProbeMutable.restart()
                }
                scheduleConnectedIpRefresh(
                    reason = IpInfoRefreshReason.NETWORK_CHANGE,
                    clearExistingIp = false,
                    showLoading = false,
                    minimumLoadingDurationMs = 0L,
                )
            } else if (shouldRefreshConnectedIp) {
                scheduleConnectedIpRefresh()
            } else if (shouldRefreshIdleIp && !autoConnectUiStateMutable.value.running) {
                startIpInfoRefresh(
                    reportFailures = false,
                    showLoading = false,
                    clearExistingIp = false,
                    fetchMode = IpInfoFetchMode.ENTRY_QUICK,
                    minimumLoadingDurationMs = 0L,
                    reason = IpInfoRefreshReason.FOREGROUND,
                    targetOverride = IpInfoRefreshTarget.UPSTREAM,
                )
            }
        }
    }
}

/** Tor-operation lifecycle: finish/clear/disable the operation as snapshot + exit IP evolve. */
internal fun HomeViewModel.startTorOperationSupervision() {
    viewModelScope.launch {
        combine(
            container.connectionController.snapshot,
            torIpInfoMutable,
            torOperationMutable,
        ) { snapshot, torIpInfo, torOperation ->
            Triple(snapshot, torIpInfo, torOperation)
        }.collect { (snapshot, torIpInfo, torOperation) ->
            val torRouteEngaged =
                snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID || snapshot.torActive
            if (!torRouteEngaged && !torOperation.active) {
                cancelTorIdentityProbe()
            } else if (
                torRouteEngaged &&
                snapshot.state == ConnectionState.CONNECTED &&
                torIdentityProbeMutable.state.value.phase == TorIdentityProbePhase.CANCELLED
            ) {
                // A connected runtime can be restored after the ViewModel first observed IDLE.
                // Give that live Tor session its own generation instead of letting the cold-start
                // cancellation fence reject the runtime-owned exit identity forever.
                torIdentityProbeMutable.restart()
            }
            val completionIpInfo =
                torOperationCompletionIpInfo(
                    snapshot = snapshot,
                    torOperation = torOperation,
                    currentTorIpInfo = torIpInfo,
                )
            when {
                snapshot.state == ConnectionState.ERROR && torOperation.active ->
                    disableTorOperationAfterRuntimeError(snapshot, torOperation)
                completionIpInfo != null -> maybeFinishTorOperation(torOperation, completionIpInfo)
                shouldClearTorOperationAfterValidatedConnect(
                    snapshot = snapshot,
                    torOperation = torOperation,
                ) -> clearTorOperation()
            }
            superviseTorExitBackgroundRefresh(
                snapshot = snapshot,
                torIpInfo = torIpInfo,
                torOperation = torOperation,
            )
        }
    }
}

/**
 * The runtime publishes the real Tor exit (probed through tunnel -> runtime -> Tor) here when Tor
 * is over the VPN. The app's own probe is excluded from Tor, so this is the only way the map
 * learns the Tor exit; reuse the same accept guard as the TOR_ROUTE refresh.
 */
internal fun HomeViewModel.startRuntimeTorExitSupervision() {
    viewModelScope.launch {
        container.connectionController.torRouteIpInfo.collect { runtimeTorExit ->
            if (runtimeTorExit != null) {
                publishTorRouteExit(runtimeTorExit)
            }
        }
    }
}

/**
 * Foreground entry point: refresh usage access, the app-traffic sampler, and — depending on the
 * runtime state — either reconcile a restored VPN network or kick the ordinary IP refresh.
 */
internal fun HomeViewModel.onAppForegroundedInternal() {
    foregroundRefreshJob?.takeIf { job -> job.isActive }?.let {
        container.diagnosticsLogger.record("ip", "foreground refresh skipped: active")
        return
    }
    val now = SystemClock.elapsedRealtime()
    if (
        lastAppForegroundRefreshElapsedMs > 0L &&
        now - lastAppForegroundRefreshElapsedMs < APP_FOREGROUND_REFRESH_MIN_INTERVAL_MS
    ) {
        container.diagnosticsLogger.record("ip", "foreground refresh skipped: debounce")
        return
    }
    lastAppForegroundRefreshElapsedMs = now
    foregroundRefreshJob = viewModelScope.launch {
        if (!firstAppForegroundHandled) {
            firstAppForegroundHandled = true
            delay(FIRST_FOREGROUND_REFRESH_STARTUP_DELAY_MS)
        }
        appTrafficUsageAccessGrantedMutable.value =
            withContext(Dispatchers.IO) { appTrafficStatsRecorder.hasUsageAccess() }
        val appTrafficStatsAllowed =
            appTrafficStatsRuntimeAllowed(
                settings = container.settingsRepository.settings.value,
            )
        syncAppTrafficStatsSampler(appTrafficStatsAllowed)
        if (statisticsVisible && appTrafficStatsAllowed) {
            sampleAppTrafficStats()
        }
        val reconciledActiveVpn = container.connectionController.reconcileActiveVpnNetworkIfNeeded()
        if (reconciledActiveVpn) {
            scheduleConnectedIpRefresh(reason = IpInfoRefreshReason.RESTORED_VPN, clearExistingIp = false)
        } else {
            syncLocalGuardWithPermissionRequest()
            refreshIpInfoOnForegroundIfNeeded()
        }
    }
}

private fun HomeViewModel.refreshIpInfoOnForegroundIfNeeded() {
    val runtimeState = container.connectionController.snapshot.value.state
    if (!shouldAutoRefreshIpOnForeground(runtimeState) || ipInfoLoadingMutable.value) {
        return
    }
    if (runtimeState == ConnectionState.CONNECTED) {
        scheduleForegroundDashboardRefreshIfStale()
    } else {
        startIpInfoRefresh(
            reportFailures = false,
            showLoading = false,
            clearExistingIp = false,
            fetchMode = IpInfoFetchMode.ENTRY_QUICK,
            minimumLoadingDurationMs = 0L,
        )
    }
}
