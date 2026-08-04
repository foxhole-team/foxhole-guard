package com.foxhole.core.sentinel.detection

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

    /**
     * Median absolute deviation with a floor. The floor prevents a near-constant history from
     * exploding the z-score, but it is scale-dependent: the default of 1.0 assumes metrics in the
     * hundreds or larger (bytes/min, latency ms, ratio x100). Metrics on a smaller scale must pass
     * their own [floor].
     */
    fun mad(
        values: List<Double>,
        median: Double,
        floor: Double = DEFAULT_MAD_FLOOR,
    ): Double {
        if (values.isEmpty()) {
            return 0.0
        }
        return median(values.map { kotlin.math.abs(it - median) }).coerceAtLeast(floor)
    }

    fun robustZ(
        value: Double,
        history: List<Double>,
        minSamples: Int = MIN_SAMPLES,
        madFloor: Double = DEFAULT_MAD_FLOOR,
    ): Double {
        if (history.size < minSamples) {
            return 0.0
        }
        val median = median(history)
        return robustZFromStats(value, median, mad(history, median, madFloor), madFloor)
    }

    /** z from already-computed robust stats, e.g. a persisted baseline that outlived raw windows. */
    fun robustZFromStats(
        value: Double,
        median: Double,
        mad: Double,
        madFloor: Double = DEFAULT_MAD_FLOOR,
    ): Double = NORMAL_CONSISTENCY * (value - median) / mad.coerceAtLeast(madFloor)

    const val MIN_SAMPLES = 12
    const val DEFAULT_MAD_FLOOR = 1.0
    private const val NORMAL_CONSISTENCY = 0.6745
}
