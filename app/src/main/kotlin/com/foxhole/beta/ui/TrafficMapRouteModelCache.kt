package com.foxhole.beta.ui

import android.util.LruCache
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

internal data class TrafficMapRouteDrawCacheKey(
    val edges: List<DrawableTrafficMapEdge>,
    val originX: Int,
    val originY: Int,
    val viewportTopLeft: IntOffset,
    val viewportSize: IntSize,
    val maxBytes: Long,
    val minLineStrokeKey: Int,
    val maxLineStrokeKey: Int,
)

internal object TrafficMapRouteModelCache {
    private val cache =
        LruCache<TrafficMapRouteDrawCacheKey, List<TrafficMapRouteDrawModel>>(
            TRAFFIC_MAP_ROUTE_MODEL_CACHE_SIZE,
        )

    @Synchronized
    fun getOrBuild(
        key: TrafficMapRouteDrawCacheKey,
        builder: () -> List<TrafficMapRouteDrawModel>,
    ): List<TrafficMapRouteDrawModel> {
        cache.get(key)?.let { return it }
        return builder().also { models -> cache.put(key, models) }
    }
}

internal fun trafficMapEdgeLaneKey(edge: DrawableTrafficMapEdge): String =
    "${edge.role}:${edge.toLat}:${edge.toLon}"

internal fun routeLane(
    bucket: Int,
    indexInBucket: Int,
): Int {
    val magnitude = (indexInBucket / 2) + 1
    val sign = if ((bucket + indexInBucket) % 2 == 0) 1 else -1
    return sign * magnitude
}

private const val TRAFFIC_MAP_ROUTE_MODEL_CACHE_SIZE = 64
