package com.foxhole.guard.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeTileDependencies
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.publishManualIdentityRefresh
import com.foxhole.guard.runtime.reconcileActiveVpnNetworkIfNeeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

internal enum class StatusWidgetRefreshPhase { IDLE, LOADING, FAILED }

internal object StatusWidgetRefreshState {
    private val phaseMutable = MutableStateFlow(StatusWidgetRefreshPhase.IDLE)
    private val frameMutable = MutableStateFlow(0)
    private val running = AtomicBoolean(false)
    val phase = phaseMutable.asStateFlow()
    val frame = frameMutable.asStateFlow()

    suspend fun run(
        renderFrame: suspend () -> Unit,
        block: suspend () -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        phaseMutable.value = StatusWidgetRefreshPhase.LOADING
        coroutineScope {
            val animator = launch {
                while (isActive) {
                    renderFrame()
                    delay(STATUS_WIDGET_REFRESH_FRAME_MS)
                    frameMutable.value = (frameMutable.value + 1) % STATUS_WIDGET_REFRESH_FRAME_COUNT
                }
            }
            try {
                withTimeout(STATUS_WIDGET_REFRESH_TIMEOUT_MS) { block() }
                phaseMutable.value = StatusWidgetRefreshPhase.IDLE
            } catch (_: TimeoutCancellationException) {
                phaseMutable.value = StatusWidgetRefreshPhase.FAILED
            } catch (cancelled: CancellationException) {
                phaseMutable.value = StatusWidgetRefreshPhase.IDLE
                throw cancelled
            } catch (_: Exception) {
                phaseMutable.value = StatusWidgetRefreshPhase.FAILED
            } finally {
                animator.cancelAndJoin()
                frameMutable.value = 0
                running.set(false)
            }
        }
    }
}

class StatusWidgetCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val application = context.applicationContext as? FoxholeApplication ?: return
        val pendingResult = goAsync()
        application.appScope.launch {
            try {
                handle(application, action)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(application: FoxholeApplication, action: String) {
        val dependencies: FoxholeTileDependencies = application.appGraph
        dependencies.connectionController.reconcileActiveVpnNetworkIfNeeded()
        val snapshot = dependencies.connectionController.snapshot.value
        val settings = dependencies.settingsRepository.settings.value
        val trafficMode = settings.traffic.mode
        when (action) {
            ACTION_START -> if (!widgetHasPrimaryConnection(snapshot)) {
                FoxholeConnectionServiceContract.startForegroundService(
                    context = application,
                    mode = trafficMode,
                    action = FoxholeConnectionServiceContract.ACTION_RESTORE,
                )
            }
            ACTION_STOP -> if (widgetHasPrimaryConnection(snapshot)) {
                FoxholeConnectionServiceContract.startForegroundService(
                    context = application,
                    mode = FoxholeConnectionServiceContract.serviceMode(snapshot, trafficMode),
                    action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                    suppressLocalGuard = false,
                )
            }
            ACTION_RESTART -> if (widgetHasPrimaryConnection(snapshot)) {
                FoxholeConnectionServiceContract.startForegroundService(
                    context = application,
                    mode = FoxholeConnectionServiceContract.serviceMode(snapshot, trafficMode),
                    action = FoxholeConnectionServiceContract.ACTION_RELOAD,
                    profileId = snapshot.profileId,
                )
            }
            ACTION_REFRESH -> refreshIdentities(application, dependencies, snapshot)
            ACTION_TOGGLE -> if (widgetHasPrimaryConnection(snapshot)) {
                handle(application, ACTION_STOP)
            } else {
                handle(application, ACTION_START)
            }
        }
    }

    private suspend fun refreshIdentities(
        application: FoxholeApplication,
        dependencies: FoxholeTileDependencies,
        expected: ConnectionSnapshot,
    ) {
        StatusWidgetRefreshState.run(renderFrame = { StatusWidget().updateAll(application) }) {
            val profileVpn = expected.profileId?.let { it > 0L } == true
            val tor = expected.profileId == TOR_ONLY_PROFILE_ID || expected.torActive
            val (deviceInfo, vpnInfo, torInfo) = supervisorScope {
                val device = async {
                    refreshWidgetIdentity(dependencies, "device") {
                        dependencies.connectionController.refreshDeviceIpInfo(WIDGET_IP_INFO_FETCH_MODE)
                    }
                }
                val vpn = if (profileVpn) {
                    async {
                        refreshWidgetIdentity(dependencies, "vpn") {
                            dependencies.connectionController.refreshIpInfo(WIDGET_IP_INFO_FETCH_MODE)
                        }
                    }
                } else {
                    null
                }
                val torExit = if (tor) {
                    async {
                        refreshWidgetIdentity(dependencies, "tor") {
                            dependencies.connectionController.refreshTorRouteIpInfo(WIDGET_IP_INFO_FETCH_MODE)
                        }
                    }
                } else {
                    null
                }
                Triple(device.await(), vpn?.await(), torExit?.await())
            }
            dependencies.connectionController.publishManualIdentityRefresh(
                expected = expected,
                vpnInfo = vpnInfo,
                torInfo = torInfo,
                deviceInfo = deviceInfo,
            )
        }
        StatusWidget().updateAll(application)
    }

    private suspend fun refreshWidgetIdentity(
        dependencies: FoxholeTileDependencies,
        label: String,
        block: suspend () -> com.foxhole.core.model.IpInfo,
    ): com.foxhole.core.model.IpInfo? {
        return try {
            withTimeoutOrNull(STATUS_WIDGET_IDENTITY_TIMEOUT_MS) { block() }
                ?: run {
                    dependencies.diagnosticsLogger.recordFailure(
                        "widget",
                        "identity refresh timed out target=$label",
                    )
                    null
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            dependencies.diagnosticsLogger.recordFailure(
                "widget",
                "identity refresh failed target=$label error=${error.javaClass.simpleName}",
            )
            null
        }
    }

    companion object {
        const val ACTION_START = "com.foxhole.guard.widget.action.START"
        const val ACTION_STOP = "com.foxhole.guard.widget.action.STOP"
        const val ACTION_RESTART = "com.foxhole.guard.widget.action.RESTART"
        const val ACTION_REFRESH = "com.foxhole.guard.widget.action.REFRESH"
        const val ACTION_TOGGLE = "com.foxhole.guard.widget.action.TOGGLE"
    }
}

private const val STATUS_WIDGET_REFRESH_TIMEOUT_MS = 18_000L
private const val STATUS_WIDGET_IDENTITY_TIMEOUT_MS = 16_000L
private const val STATUS_WIDGET_REFRESH_FRAME_MS = 240L
internal const val STATUS_WIDGET_REFRESH_FRAME_COUNT = 4
private val WIDGET_IP_INFO_FETCH_MODE = IpInfoFetchMode.ENTRY_QUICK
