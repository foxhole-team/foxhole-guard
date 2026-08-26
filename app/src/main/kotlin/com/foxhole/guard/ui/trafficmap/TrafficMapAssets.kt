package com.foxhole.guard.ui.trafficmap

import android.content.Context
import android.os.Trace
import android.util.Log
import com.foxhole.guard.traffic.TRAFFIC_MAP_COUNTRY_SHAPES_ASSET
import com.foxhole.guard.traffic.TrafficMapCountryShape
import com.foxhole.guard.traffic.TrafficMapCountryShapeAssetParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal sealed interface TrafficMapAssetState {
    data object Idle : TrafficMapAssetState
    data object Loading : TrafficMapAssetState
    data class Ready(val shapes: List<TrafficMapCountryShape>) : TrafficMapAssetState
    data object Error : TrafficMapAssetState
}

internal object TrafficMapAssets {
    private const val TAG = "TrafficMapAssets"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<TrafficMapAssetState>(TrafficMapAssetState.Idle)

    val state: StateFlow<TrafficMapAssetState> = _state.asStateFlow()

    fun prewarm(context: Context) {
        if (!_state.compareAndSet(TrafficMapAssetState.Idle, TrafficMapAssetState.Loading)) return
        val appContext = context.applicationContext
        scope.launch {
            _state.value = load(appContext)
        }
    }

    private suspend fun load(context: Context): TrafficMapAssetState =
        withContext(Dispatchers.IO) {
            try {
                traceTrafficMapAssetLoad {
                    context.assets.open(TRAFFIC_MAP_COUNTRY_SHAPES_ASSET).use { input ->
                        val shapes = TrafficMapCountryShapeAssetParser().parse(input)
                        if (shapes.isEmpty()) {
                            TrafficMapAssetState.Error
                        } else {
                            TrafficMapAssetState.Ready(shapes)
                        }
                    }
                }
            } catch (error: IOException) {
                Log.w(TAG, "Failed to load traffic map shapes", error)
                TrafficMapAssetState.Error
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Malformed traffic map shapes", error)
                TrafficMapAssetState.Error
            }
        }
}

private inline fun <T> traceTrafficMapAssetLoad(block: () -> T): T {
    Trace.beginSection(TRAFFIC_MAP_LOAD_SHAPES_TRACE)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

internal const val TRAFFIC_MAP_LOAD_SHAPES_TRACE = "TrafficMap/loadShapes"
