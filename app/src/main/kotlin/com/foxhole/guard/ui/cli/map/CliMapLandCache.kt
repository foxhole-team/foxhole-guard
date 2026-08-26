package com.foxhole.guard.ui.cli.map

import android.graphics.Bitmap
import android.os.Trace
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import com.foxhole.guard.traffic.TrafficMapCountryShape
import com.foxhole.guard.traffic.TrafficMapGeoPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath

internal object CliMapLandCache {
    private val lock = Any()
    private val bitmaps =
        CliMapByteLru<CliMapLandKey, ImageBitmap>(MAX_CACHE_BYTES) { bitmap ->
            cliMapBitmapByteCount(bitmap.width, bitmap.height)
        }
    private val inFlight = mutableMapOf<CliMapLandKey, CompletableDeferred<ImageBitmap>>()

    suspend fun bitmap(
        shapes: List<TrafficMapCountryShape>,
        canvasSize: IntSize,
        fillColor: Color?,
        boundaryColor: Color,
        pointStride: Int,
        antiAlias: Boolean = false,
    ): ImageBitmap = withContext(Dispatchers.Default) {
        val key = CliMapLandKey(
            width = canvasSize.width.coerceAtLeast(1),
            height = canvasSize.height.coerceAtLeast(1),
            fillColor = fillColor?.toArgb(),
            boundaryColor = boundaryColor.toArgb(),
            shapesIdentity = CliMapShapesIdentity(shapes),
            pointStride = pointStride.coerceAtLeast(1),
            antiAlias = antiAlias,
        )
        var renderOwner = false
        val pending =
            synchronized(lock) {
                bitmaps[key]?.let { cached -> return@withContext cached }
                inFlight[key]
                    ?: CompletableDeferred<ImageBitmap>().also { deferred ->
                        inFlight[key] = deferred
                        renderOwner = true
                    }
            }

        if (!renderOwner) return@withContext pending.await()

        try {
            val rendered = traceMapLandSection { renderLand(shapes, key) }
            synchronized(lock) {
                bitmaps[key] = rendered
                inFlight.remove(key)
            }
            pending.complete(rendered)
            rendered
        } catch (error: Throwable) {
            synchronized(lock) { inFlight.remove(key) }
            pending.completeExceptionally(error)
            throw error
        }
    }

    private const val MAX_CACHE_BYTES = 12L * 1024L * 1024L
}

internal class CliMapByteLru<K, V>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    private val entries = LinkedHashMap<K, V>(4, 0.75f, true)

    var byteCount: Long = 0L
        private set

    val size: Int
        get() = entries.size

    operator fun get(key: K): V? = entries[key]

    operator fun set(key: K, value: V) {
        val valueBytes = sizeOf(value).coerceAtLeast(0L)
        if (valueBytes > maxBytes) return
        entries.put(key, value)?.let { previous -> byteCount -= sizeOf(previous).coerceAtLeast(0L) }
        byteCount += valueBytes
        while (byteCount > maxBytes && entries.size > 1) {
            val eldest = entries.entries.first()
            entries.remove(eldest.key)
            byteCount -= sizeOf(eldest.value).coerceAtLeast(0L)
        }
    }

    internal fun contains(key: K): Boolean = entries.containsKey(key)
}

internal fun cliMapBitmapByteCount(width: Int, height: Int): Long {
    val rowBytes = width.coerceAtLeast(1).toLong() * ARGB_8888_BYTES_PER_PIXEL
    val safeHeight = height.coerceAtLeast(1).toLong()
    return if (safeHeight > Long.MAX_VALUE / rowBytes) Long.MAX_VALUE else rowBytes * safeHeight
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
                isAntiAlias = key.antiAlias
            }
        )
    }
    canvas.drawPath(
        path,
        AndroidPaint().apply {
            style = AndroidPaint.Style.STROKE
            strokeWidth = 1f
            color = key.boundaryColor
            isAntiAlias = key.antiAlias
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
    val shapesIdentity: CliMapShapesIdentity,
    val pointStride: Int,
    val antiAlias: Boolean = false,
)

private class CliMapShapesIdentity(
    private val shapes: List<TrafficMapCountryShape>,
) {
    override fun equals(other: Any?): Boolean =
        other is CliMapShapesIdentity && shapes === other.shapes

    override fun hashCode(): Int = System.identityHashCode(shapes)
}

private inline fun <T> traceMapLandSection(block: () -> T): T {
    Trace.beginSection(TRAFFIC_MAP_RENDER_LAND_BITMAP_TRACE)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

private const val MIN_LAT = -55.0
private const val MAX_LAT = 85.0
private const val LAT_RANGE = MAX_LAT - MIN_LAT
private const val ARGB_8888_BYTES_PER_PIXEL = 4L
internal const val TRAFFIC_MAP_RENDER_LAND_BITMAP_TRACE = "TrafficMap/renderLandBitmap"
