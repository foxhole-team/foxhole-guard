package com.foxhole.guard.traffic

import kotlin.math.abs
import kotlin.math.ceil

fun TrafficMapCountryShape.bounds(): GeoBounds {
    val points = rings.flatten()
    if (points.isEmpty()) {
        return GeoBounds(minLat = 0.0, maxLat = 0.0, minLon = 0.0, maxLon = 0.0)
    }
    return GeoBounds(
        minLat = points.minOf(TrafficMapGeoPoint::lat),
        maxLat = points.maxOf(TrafficMapGeoPoint::lat),
        minLon = points.minOf { point -> normalizeTrafficMapLongitude(point.lon) },
        maxLon = points.maxOf { point -> normalizeTrafficMapLongitude(point.lon) },
    )
}

fun TrafficMapCountryShape.visualCentroid(): TrafficMapGeoPoint {
    val largestRing =
        rings
            .asSequence()
            .map(::normalizeTrafficMapRingLongitudes)
            .filter { ring -> ring.size >= TRAFFIC_MAP_VISUAL_MIN_RING_POINTS }
            .maxByOrNull(::trafficMapRingArea)
            ?: return TrafficMapGeoPoint(lat = 0.0, lon = 0.0)
    return trafficMapPolygonCentroid(largestRing)
}

internal fun TrafficMapCountryShape.projectedAreaPixels(
    widthPx: Double,
    heightPx: Double,
    minLat: Double = TRAFFIC_MAP_PROJECTED_MIN_LAT,
    maxLat: Double = TRAFFIC_MAP_PROJECTED_MAX_LAT,
): Double {
    val latRange = maxLat - minLat
    if (widthPx <= 0.0 || heightPx <= 0.0 || latRange <= 0.0) {
        return 0.0
    }
    return rings
        .asSequence()
        .map(::normalizeTrafficMapRingLongitudes)
        .filter { ring -> ring.size >= TRAFFIC_MAP_VISUAL_MIN_RING_POINTS }
        .sumOf { ring ->
            trafficMapProjectedRingArea(
                ring = ring,
                widthPx = widthPx,
                heightPx = heightPx,
                maxLat = maxLat,
                latRange = latRange,
            )
        }
}

internal fun normalizeTrafficMapRingLongitudes(ring: List<TrafficMapGeoPoint>): List<TrafficMapGeoPoint> {
    val normalizedRing =
        if (ring.size < TRAFFIC_MAP_VISUAL_MIN_RING_POINTS) {
            ring
        } else {
            ring.withNormalizedTrafficMapLongitudes()
        }
    return normalizedRing
}

private fun List<TrafficMapGeoPoint>.withNormalizedTrafficMapLongitudes(): List<TrafficMapGeoPoint> {
    val normalizedLongitudes = map { point -> normalizeTrafficMapLongitude(point.lon) }
    val sorted = normalizedLongitudes.distinct().sorted()
    return if (sorted.size <= 1) {
        mapIndexed { index, point -> point.copy(lon = normalizedLongitudes[index]) }
    } else {
        val intervalStart = trafficMapLongitudeIntervalStart(sorted)
        mapIndexed { index, point ->
            val longitude = normalizedLongitudes[index]
            point.copy(lon = if (longitude < intervalStart) longitude + FULL_LONGITUDE_DEGREES else longitude)
        }
    }
}

private fun trafficMapLongitudeIntervalStart(sortedLongitudes: List<Double>): Double {
    var largestGap = Double.NEGATIVE_INFINITY
    var intervalStart = sortedLongitudes.first()
    sortedLongitudes.forEachIndexed { index, longitude ->
        val next = if (index == sortedLongitudes.lastIndex) {
            sortedLongitudes.first() + FULL_LONGITUDE_DEGREES
        } else {
            sortedLongitudes[index + 1]
        }
        val gap = next - longitude
        if (gap > largestGap) {
            largestGap = gap
            intervalStart =
                if (index == sortedLongitudes.lastIndex) {
                    sortedLongitudes.first()
                } else {
                    sortedLongitudes[index + 1]
                }
        }
    }
    return intervalStart
}

internal fun trafficMapRingArea(ring: List<TrafficMapGeoPoint>): Double {
    if (ring.size < TRAFFIC_MAP_VISUAL_MIN_RING_POINTS) {
        return 0.0
    }
    var area = 0.0
    ring.indices.forEach { index ->
        val current = ring[index]
        val next = ring[(index + 1) % ring.size]
        area += current.lon * next.lat - next.lon * current.lat
    }
    return abs(area) / 2.0
}

private fun trafficMapProjectedRingArea(
    ring: List<TrafficMapGeoPoint>,
    widthPx: Double,
    heightPx: Double,
    maxLat: Double,
    latRange: Double,
): Double {
    var area = 0.0
    ring.indices.forEach { index ->
        val current = ring[index].projectedTrafficMapPoint(widthPx, heightPx, maxLat, latRange)
        val next = ring[(index + 1) % ring.size].projectedTrafficMapPoint(widthPx, heightPx, maxLat, latRange)
        area += current.x * next.y - next.x * current.y
    }
    return abs(area) / 2.0
}

private data class TrafficMapProjectedPoint(
    val x: Double,
    val y: Double,
)

private fun TrafficMapGeoPoint.projectedTrafficMapPoint(
    widthPx: Double,
    heightPx: Double,
    maxLat: Double,
    latRange: Double,
): TrafficMapProjectedPoint =
    TrafficMapProjectedPoint(
        x = ((lon + HALF_LONGITUDE_DEGREES) / FULL_LONGITUDE_DEGREES) * widthPx,
        y = ((maxLat - lat) / latRange) * heightPx,
    )

internal fun simplifyTrafficMapRing(
    ring: List<TrafficMapGeoPoint>,
    maxPoints: Int = TRAFFIC_MAP_VISUAL_MAX_POINTS_PER_RING,
): List<TrafficMapGeoPoint> {
    val openRing =
        if (ring.size > 1 && ring.first() == ring.last()) {
            ring.dropLast(1)
        } else {
            ring
        }
    val simplifiedRing =
        if (openRing.size <= maxPoints) {
            openRing
        } else {
            openRing.simplifyTrafficMapRingByDistance(maxPoints)
        }
    return simplifiedRing
}

private fun List<TrafficMapGeoPoint>.simplifyTrafficMapRingByDistance(maxPoints: Int): List<TrafficMapGeoPoint> {
    val byDistance = trafficMapRingPointsByMinimumDistance()
    val reduced =
        byDistance.takeIf { points -> points.size >= TRAFFIC_MAP_VISUAL_MIN_RING_POINTS } ?: this
    return if (reduced.size <= maxPoints) {
        reduced
    } else {
        reduced.sampleTrafficMapRing(maxPoints)
    }
}

private fun List<TrafficMapGeoPoint>.trafficMapRingPointsByMinimumDistance(): List<TrafficMapGeoPoint> {
    val byDistance = mutableListOf<TrafficMapGeoPoint>()
    forEach { point ->
        val previous = byDistance.lastOrNull()
        if (
            previous == null ||
            point.distanceFrom(previous) >= TRAFFIC_MAP_VISUAL_MIN_POINT_DELTA_DEGREES
        ) {
            byDistance += point
        }
    }
    return byDistance
}

private fun TrafficMapGeoPoint.distanceFrom(previous: TrafficMapGeoPoint): Double =
    abs(lat - previous.lat) + abs(lon - previous.lon)

private fun List<TrafficMapGeoPoint>.sampleTrafficMapRing(maxPoints: Int): List<TrafficMapGeoPoint> {
    val stride = ceil(size.toDouble() / maxPoints.toDouble()).toInt().coerceAtLeast(1)
    val sampled = filterIndexed { index, _ -> index % stride == 0 }
    return sampled.takeIf { points -> points.size >= TRAFFIC_MAP_VISUAL_MIN_RING_POINTS } ?: take(maxPoints)
}

private fun normalizeTrafficMapLongitude(lon: Double): Double {
    var normalized = lon % FULL_LONGITUDE_DEGREES
    if (normalized < -HALF_LONGITUDE_DEGREES) {
        normalized += FULL_LONGITUDE_DEGREES
    }
    if (normalized > HALF_LONGITUDE_DEGREES) {
        normalized -= FULL_LONGITUDE_DEGREES
    }
    return normalized
}

@Suppress("ReturnCount")
private fun trafficMapPolygonCentroid(ring: List<TrafficMapGeoPoint>): TrafficMapGeoPoint {
    if (ring.size < TRAFFIC_MAP_VISUAL_MIN_RING_POINTS) {
        return ring.firstOrNull() ?: TrafficMapGeoPoint(lat = 0.0, lon = 0.0)
    }
    var signedArea = 0.0
    var centroidLon = 0.0
    var centroidLat = 0.0
    ring.indices.forEach { index ->
        val current = ring[index]
        val next = ring[(index + 1) % ring.size]
        val cross = current.lon * next.lat - next.lon * current.lat
        signedArea += cross
        centroidLon += (current.lon + next.lon) * cross
        centroidLat += (current.lat + next.lat) * cross
    }
    if (abs(signedArea) < TRAFFIC_MAP_CENTROID_AREA_EPSILON) {
        return TrafficMapGeoPoint(
            lat = ring.map(TrafficMapGeoPoint::lat).average(),
            lon = normalizeTrafficMapLongitude(ring.map(TrafficMapGeoPoint::lon).average()),
        )
    }
    val area = signedArea * 0.5
    return TrafficMapGeoPoint(
        lat = centroidLat / (6.0 * area),
        lon = normalizeTrafficMapLongitude(centroidLon / (6.0 * area)),
    )
}

private const val TRAFFIC_MAP_VISUAL_MIN_RING_POINTS = 3
private const val TRAFFIC_MAP_CENTROID_AREA_EPSILON = 0.000001
private const val TRAFFIC_MAP_PROJECTED_MIN_LAT = -55.0
private const val TRAFFIC_MAP_PROJECTED_MAX_LAT = 85.0
private const val TRAFFIC_MAP_VISUAL_MAX_POINTS_PER_RING = 220
private const val TRAFFIC_MAP_VISUAL_MIN_POINT_DELTA_DEGREES = 0.045
private const val HALF_LONGITUDE_DEGREES = 180.0
private const val FULL_LONGITUDE_DEGREES = 360.0
