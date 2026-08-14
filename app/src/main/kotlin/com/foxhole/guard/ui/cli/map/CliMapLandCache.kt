package com.foxhole.guard.ui.cli.map

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import com.foxhole.guard.traffic.TrafficMapCountryShape
import com.foxhole.guard.traffic.TrafficMapGeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath

/** Small LRU for the fixed-resolution terminal land layer. */
internal object CliMapLandCache {
    private const val MAX_ENTRIES = 3
    private val lock = Any()
    private val bitmaps = LinkedHashMap<CliMapLandKey, ImageBitmap>(MAX_ENTRIES, 0.75f, true)

    suspend fun bitmap(
        shapes: List<TrafficMapCountryShape>,
        canvasSize: IntSize,
        fillColor: Color?,
        boundaryColor: Color,
        pointStride: Int,
    ): ImageBitmap = withContext(Dispatchers.Default) {
        val key = CliMapLandKey(
            width = canvasSize.width.coerceAtLeast(1),
            height = canvasSize.height.coerceAtLeast(1),
            fillColor = fillColor?.toArgb(),
            boundaryColor = boundaryColor.toArgb(),
            shapesIdentity = System.identityHashCode(shapes),
            shapeCount = shapes.size,
            pointStride = pointStride.coerceAtLeast(1),
        )
        synchronized(lock) { bitmaps[key] } ?: renderLand(shapes, key).also { rendered ->
            synchronized(lock) {
                bitmaps[key] = rendered
                while (bitmaps.size > MAX_ENTRIES) {
                    bitmaps.remove(bitmaps.entries.first().key)
                }
            }
        }
    }
}

internal fun cliProjectMap(lat: Double, lon: Double, size: androidx.compose.ui.geometry.Size): Offset {
    val normalizedLon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    val x = ((normalizedLon + 180.0) / 360.0 * size.width).toFloat()
    val clampedLat = lat.coerceIn(MIN_LAT, MAX_LAT)
    val y = ((MAX_LAT - clampedLat) / LAT_RANGE * size.height).toFloat()
    return Offset(x, y)
}

private fun renderLand(shapes: List<TrafficMapCountryShape>, key: CliMapLandKey): ImageBitmap {
    val bitmap = createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val path = AndroidPath()
    shapes.forEach { shape -> appendShape(path, shape, key) }
    key.fillColor?.let { fillColor ->
        canvas.drawPath(
            path,
            AndroidPaint().apply {
                style = AndroidPaint.Style.FILL
                color = fillColor
                isAntiAlias = false
            }
        )
    }
    canvas.drawPath(
        path,
        AndroidPaint().apply {
            style = AndroidPaint.Style.STROKE
            strokeWidth = 1f
            color = key.boundaryColor
            isAntiAlias = false
        }
    )
    return bitmap.asImageBitmap()
}

private fun appendShape(path: AndroidPath, shape: TrafficMapCountryShape, key: CliMapLandKey) {
    val size = androidx.compose.ui.geometry.Size(key.width.toFloat(), key.height.toFloat())
    shape.rings.forEach { ring ->
        var previous: TrafficMapGeoPoint? = null
        var started = false
        var split = false
        ring.forEachIndexed { index, point ->
            val boundary = index == 0 || index == ring.lastIndex
            if (!boundary && index % key.pointStride != 0) return@forEachIndexed
            val projected = cliProjectMap(point.lat, point.lon, size)
            val crossesDateLine = previous?.let { abs(it.lon - point.lon) > 180.0 } == true
            if (!started || crossesDateLine) {
                path.moveTo(projected.x, projected.y)
                started = true
                split = split || crossesDateLine
            } else {
                path.lineTo(projected.x, projected.y)
            }
            previous = point
        }
        if (!split) path.close()
    }
}

private data class CliMapLandKey(
    val width: Int,
    val height: Int,
    val fillColor: Int?,
    val boundaryColor: Int,
    val shapesIdentity: Int,
    val shapeCount: Int,
    val pointStride: Int,
)

private const val MIN_LAT = -55.0
private const val MAX_LAT = 85.0
private const val LAT_RANGE = MAX_LAT - MIN_LAT
