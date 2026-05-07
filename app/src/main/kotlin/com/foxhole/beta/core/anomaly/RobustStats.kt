package com.foxhole.beta.core.anomaly

object RobustStats {
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) {
            return 0.0
        }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    fun mad(
        values: List<Double>,
        median: Double,
    ): Double {
        if (values.isEmpty()) {
            return 0.0
        }
        return median(values.map { kotlin.math.abs(it - median) }).coerceAtLeast(1.0)
    }

    fun robustZ(
        value: Double,
        history: List<Double>,
        minSamples: Int = MinSamples,
    ): Double {
        if (history.size < minSamples) {
            return 0.0
        }
        val median = median(history)
        val mad = mad(history, median)
        return 0.6745 * (value - median) / mad
    }

    private const val MinSamples = 12
}
