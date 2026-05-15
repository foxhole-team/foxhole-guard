package com.foxhole.beta.core.statistics

private const val KB = 1024L
private const val MB = 1024L * KB
private const val GB = 1024L * MB

private val NiceByteSteps =
    listOf(
        1L * KB,
        10L * KB,
        100L * KB,
        1L * MB,
        10L * MB,
        100L * MB,
        1L * GB,
        10L * GB,
        100L * GB,
    )

fun niceBytesScale(maxBytes: Long): Long {
    val normalized = maxBytes.coerceAtLeast(1L)
    return NiceByteSteps.firstOrNull { step -> step >= normalized }
        ?: roundUpToNiceBinaryScale(normalized)
}

private fun roundUpToNiceBinaryScale(value: Long): Long {
    var unit = 100L * GB
    while (unit < Long.MAX_VALUE / 2L && unit < value) {
        unit *= 2L
    }
    return unit.coerceAtLeast(value)
}
