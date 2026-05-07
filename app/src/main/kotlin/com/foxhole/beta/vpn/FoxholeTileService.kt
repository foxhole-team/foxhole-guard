package com.foxhole.beta.vpn

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeTileDependencies
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class FoxholeTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var snapshotJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        snapshotJob?.cancel()
        snapshotJob =
            scope.launch {
                FoxholeVpnRuntimeBridge.snapshot.collect { snapshot ->
                    refreshTile(snapshot.state)
                }
            }
        refreshTile()
    }

    override fun onStopListening() {
        snapshotJob?.cancel()
        snapshotJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        snapshotJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            handleTileClick()
        }
    }

    private suspend fun handleTileClick() {
        val app = applicationContext as FoxholeApplication
        val dependencies: FoxholeTileDependencies = app.appGraph
        dependencies.connectionController.reconcileActiveVpnNetworkIfNeeded()
        val state = FoxholeVpnRuntimeBridge.snapshot.value.state
        val activating = state !in ACTIVE_STATES
        val trafficMode = dependencies.settingsRepository.settings.value.traffic.mode
        if (activating) {
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    trafficMode = trafficMode,
                    profileId = FoxholeVpnRuntimeBridge.snapshot.value.profileId,
                    profileName = FoxholeVpnRuntimeBridge.snapshot.value.profileName,
                    protocolHint = FoxholeVpnRuntimeBridge.snapshot.value.protocolHint,
                    protocolOptionId = FoxholeVpnRuntimeBridge.snapshot.value.protocolOptionId,
                ),
            )
        } else {
            FoxholeVpnRuntimeBridge.update(
                FoxholeVpnRuntimeBridge.snapshot.value.copy(
                    state = ConnectionState.IDLE,
                    message = null,
                ),
            )
        }
        updateTile(
            active = activating,
            subtitle =
                if (activating) {
                    getString(R.string.status_connecting)
                } else {
                    getString(R.string.notification_status_disconnected)
                },
        )
        if (state in ACTIVE_STATES) {
            FoxholeConnectionServiceContract.startForegroundService(
                context = applicationContext,
                mode =
                    FoxholeConnectionServiceContract.serviceMode(
                        snapshot = FoxholeVpnRuntimeBridge.snapshot.value,
                        fallbackMode = trafficMode,
                    ),
                action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
            )
        } else {
            FoxholeConnectionServiceContract.startForegroundService(
                context = applicationContext,
                mode = trafficMode,
                action = FoxholeConnectionServiceContract.ACTION_RESTORE,
            )
        }
    }

    private fun refreshTile(state: ConnectionState = FoxholeVpnRuntimeBridge.snapshot.value.state) {
        val active = state in ACTIVE_STATES
        val subtitle =
            when (state) {
                ConnectionState.CONNECTING,
                ConnectionState.RECONNECTING,
                -> getString(R.string.status_connecting)
                ConnectionState.CONNECTED -> getString(R.string.notification_status_connected)
                ConnectionState.IDLE,
                ConnectionState.ERROR,
                -> getString(R.string.notification_status_disconnected)
            }
        updateTile(active = active, subtitle = subtitle)
    }

    private fun updateTile(
        active: Boolean,
        subtitle: String,
    ) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.foxhole_logo_bitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.contentDescription = tile.label
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private companion object {
        val ACTIVE_STATES =
            setOf(
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING,
            )
    }
}
