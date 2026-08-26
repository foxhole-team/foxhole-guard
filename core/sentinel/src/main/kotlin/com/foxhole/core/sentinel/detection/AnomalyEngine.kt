package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.AnomalySensitivity
import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalySignal
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.AppBaseline
import com.foxhole.core.model.AppNetworkPresence
import com.foxhole.core.model.AppNetworkUsageCategory
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.TrafficBaseline
import com.foxhole.core.model.TrafficWindow
import java.util.Locale
import kotlin.math.roundToInt

data class AnomalyAssessment(
    val signals: List<AnomalySignal>,
    val score: Int,
    val severity: AnomalySeverity,
) {
    val shouldLogActivity: Boolean get() = severity != AnomalySeverity.SILENT
    val shouldNotify: Boolean get() = severity == AnomalySeverity.HIGH
}

data class AnomalyHistory(
    val trafficWindows: List<TrafficWindow> = emptyList(),
    val appWindowsByPackage: Map<String, List<AppTrafficWindow>> = emptyMap(),

    val everSeenCountries: Set<String>? = null,
    val observedTrafficWindowCount: Long? = null,
    val appPresence: Map<String, AppNetworkPresence> = emptyMap(),
    val trafficBaseline: TrafficBaseline? = null,
    val appBaselinesByPackage: Map<String, AppBaseline> = emptyMap(),
) {
    val knownDestinationCountries: Set<String> =
        trafficWindows
            .flatMap { window -> window.destinationCountries.keys }
            .mapTo(linkedSetOf()) { country -> country.uppercase(Locale.US) }

    val effectiveSeenCountries: Set<String>
        get() = everSeenCountries ?: knownDestinationCountries

    val learnedTrafficWindowCount: Long
        get() = observedTrafficWindowCount ?: trafficWindows.size.toLong()
}

class AnomalyEngine(
    private val detectors: List<AnomalyDetector> = defaultDetectors(),
) {
    fun evaluate(
        current: TrafficWindow,
        appWindows: List<AppTrafficWindow>,
        history: AnomalyHistory,
        settings: AnomalySettings = AnomalySettings(),
        appCategories: Map<String, AppNetworkUsageCategory> = emptyMap(),
    ): AnomalyAssessment {
        if (!settings.enabled) {
            return AnomalyAssessment(
                signals = emptyList(),
                score = 0,
                severity = AnomalySeverity.SILENT,
            )
        }

        val excluded = settings.excludedPackages.toSet()
        val analyzedAppWindows =
            if (excluded.isEmpty()) appWindows else appWindows.filterNot { it.packageName in excluded }
        val analyzedHistory =
            if (excluded.isEmpty()) {
                history
            } else {
                history.copy(
                    appWindowsByPackage = history.appWindowsByPackage - excluded,
                    appPresence = history.appPresence - excluded,
                    appBaselinesByPackage = history.appBaselinesByPackage - excluded,
                )
            }
        val context =
            AnomalyDetectionContext(
                current = current,
                appWindows = analyzedAppWindows,
                history = analyzedHistory,
                settings = settings,
                appCategories = if (excluded.isEmpty()) appCategories else appCategories - excluded,
            )
        val signals =
            detectors
                .flatMap { detector -> detector.detect(context) }
                .filter { signal -> signal.severity > 0.0 }
        val score = combineSignals(signals)
        return AnomalyAssessment(
            signals = signals,
            score = score,
            severity = severityForScore(score, settings.sensitivity),
        )
    }

    companion object {
        fun combineSignals(signals: List<AnomalySignal>): Int {
            val combined =
                signals.fold(0.0) { acc, signal ->
                    1.0 - (1.0 - acc) * (1.0 - signal.severity.coerceIn(0.0, 1.0))
                }
            return (combined * 100.0).roundToInt().coerceIn(0, 100)
        }

        fun severityForScore(
            score: Int,
            sensitivity: AnomalySensitivity,
        ): AnomalySeverity {
            val thresholds = thresholdsFor(sensitivity)
            return when {
                score >= thresholds.high -> AnomalySeverity.HIGH
                score >= thresholds.notification -> AnomalySeverity.NOTIFICATION
                score >= thresholds.activityLog -> AnomalySeverity.ACTIVITY_LOG
                else -> AnomalySeverity.SILENT
            }
        }

        fun thresholdsFor(sensitivity: AnomalySensitivity): AnomalyThresholds =
            when (sensitivity) {
                AnomalySensitivity.NORMAL -> AnomalyThresholds(activityLog = 40, notification = 70, high = 85)
                AnomalySensitivity.STRICT -> AnomalyThresholds(activityLog = 30, notification = 60, high = 80)
            }
    }
}

data class AnomalyThresholds(
    val activityLog: Int,
    val notification: Int,
    val high: Int,
)

interface AnomalyDetector {
    fun detect(context: AnomalyDetectionContext): List<AnomalySignal>
}

data class AnomalyDetectionContext(
    val current: TrafficWindow,
    val appWindows: List<AppTrafficWindow>,
    val history: AnomalyHistory,
    val settings: AnomalySettings,
    val appCategories: Map<String, AppNetworkUsageCategory> = emptyMap(),
) {
    fun categoryOf(packageName: String): AppNetworkUsageCategory =
        appCategories[packageName] ?: AppNetworkUsageCategory.STANDARD
}

private fun defaultDetectors(): List<AnomalyDetector> =
    listOf(
        AppUploadSpikeDetector(),
        AppBackgroundTrafficDetector(),
        TotalTrafficSpikeDetector(),
        NewDestinationCountryDetector(),
        DnsBlockRatioSpikeDetector(),
        ReconnectStormDetector(),
        LatencyShiftDetector(),
        DormantAppNetworkActivityDetector(),
    )

class AppUploadSpikeDetector : AnomalyDetector {
    override fun detect(context: AnomalyDetectionContext): List<AnomalySignal> =
        context.appWindows.mapNotNull { appWindow -> detectForApp(context, appWindow) }

    @Suppress("ComplexCondition")
    private fun detectForApp(
        context: AnomalyDetectionContext,
        appWindow: AppTrafficWindow,
    ): AnomalySignal? {
        val zThreshold = context.settings.zScoreThreshold()
        val totalBytes = context.current.totalBytes.coerceAtLeast(1L)
        val history =
            context.history.appWindowsByPackage[appWindow.packageName]
                .orEmpty()
                .map { window -> window.txBytes.perMinute(window.durationSec) }
        val baseline = context.history.appBaselinesByPackage[appWindow.packageName]
        val txPerMin = appWindow.txBytes.perMinute(appWindow.durationSec)
        val z =
            robustZWithBaselineFallback(txPerMin, history, baseline?.median, baseline?.mad, baseline?.sampleCount ?: 0)
        val appShare = appWindow.totalBytes.toDouble() / totalBytes.toDouble()
        val uploadRatio = appWindow.txBytes.toDouble() / appWindow.totalBytes.coerceAtLeast(1L).toDouble()
        val learned = history.size >= LEARNING_SAMPLES || (baseline?.sampleCount ?: 0) >= LEARNING_SAMPLES

        val shareBoost =
            if (z >= zThreshold && appShare >= 0.55 && uploadRatio >= 0.55 && learned) {
                0.28
            } else {
                0.0
            }
        var severity = (((z - zThreshold) / 6.0).coerceIn(0.0, 1.0) + shareBoost).coerceIn(0.0, 1.0)

        val contentDampened =
            context.categoryOf(appWindow.packageName) == AppNetworkUsageCategory.CONTENT_HEAVY &&
                uploadRatio < CONTENT_HEAVY_UPLOAD_RATIO_GATE
        if (contentDampened) {
            severity *= CONTENT_HEAVY_DAMPEN_FACTOR
        }
        if (severity <= 0.0) {
            return null
        }
        return AnomalySignal(
            type = AnomalyType.APP_UPLOAD_SPIKE,
            severity = severity,
            reason = "App upload is much higher than its local baseline",
            evidence =
            buildMap {
                put("package", appWindow.packageName)
                put("tx_per_min", txPerMin.toLong().toString())
                put("robust_z", "%.2f".format(Locale.US, z))
                put("app_share", "%.2f".format(Locale.US, appShare))
                put("upload_ratio", "%.2f".format(Locale.US, uploadRatio))
                if (contentDampened) {
                    put("content_dampened", "true")
                }
            },
        )
    }
}

class AppBackgroundTrafficDetector : AnomalyDetector {
    @Suppress("CyclomaticComplexMethod")
    override fun detect(context: AnomalyDetectionContext): List<AnomalySignal> {
        if (!context.settings.analyzeBackgroundTraffic) {
            return emptyList()
        }
        val totalBytes = context.current.totalBytes.coerceAtLeast(1L)
        return context.appWindows.mapNotNull { appWindow ->
            if (appWindow.foreground != false || appWindow.totalBytes <= 0L) {
                return@mapNotNull null
            }
            val history = context.history.appWindowsByPackage[appWindow.packageName].orEmpty()
            val baseline = context.history.appBaselinesByPackage[appWindow.packageName]
            if (history.size < LEARNING_SAMPLES && (baseline?.sampleCount ?: 0) < LEARNING_SAMPLES) {
                return@mapNotNull null
            }
            val share = appWindow.totalBytes.toDouble() / totalBytes.toDouble()
            val uploadRatio = appWindow.txBytes.toDouble() / appWindow.totalBytes.coerceAtLeast(1L).toDouble()
            val severity =
                when {
                    share >= 0.65 && uploadRatio >= 0.45 -> 0.78
                    share >= 0.45 && uploadRatio >= 0.35 -> 0.58
                    share >= 0.30 && uploadRatio >= 0.50 -> 0.44
                    else -> 0.0
                }
            severity
                .takeIf { it > 0.0 }
                ?.let {
                    AnomalySignal(
                        type = AnomalyType.APP_BACKGROUND_TRAFFIC,
                        severity = it,
                        reason = "App used unusually high traffic while in background",
                        evidence =
                        mapOf(
                            "package" to appWindow.packageName,
                            "app_share" to "%.2f".format(Locale.US, share),
                            "upload_ratio" to "%.2f".format(Locale.US, uploadRatio),
                        ),
                    )
                }
        }
    }
}

class TotalTrafficSpikeDetector : AnomalyDetector {
    override fun detect(context: AnomalyDetectionContext): List<AnomalySignal> {
        val history = context.history.trafficWindows.map { it.totalBytes.perMinute(it.durationSec) }
        val totalPerMin = context.current.totalBytes.perMinute(context.current.durationSec)
        val baseline = context.history.trafficBaseline
        val z =
            robustZWithBaselineFallback(
                totalPerMin,
                history,
                baseline?.median,
                baseline?.mad,
                baseline?.sampleCount ?: 0
            )
        val zThreshold = context.settings.zScoreThreshold()
        if (z < zThreshold) {
            return emptyList()
        }
        var severity = ((z - zThreshold) / 6.0).coerceIn(0.0, 1.0)

        val dominant = dominantContentHeavyApp(context)
        if (dominant != null) {
            severity *= CONTENT_HEAVY_DAMPEN_FACTOR
        }
        return listOf(
            AnomalySignal(
                type = AnomalyType.TOTAL_TRAFFIC_SPIKE,
                severity = severity,
                reason = "Total traffic is much higher than the local baseline",
                evidence =
                buildMap {
                    put("bytes_per_min", totalPerMin.toLong().toString())
                    put("robust_z", "%.2f".format(Locale.US, z))
                    if (dominant != null) {
                        put("content_dampened", "true")
                        put("dominant_package", dominant.packageName)
                    }
                },
            ),
        )
    }

    private fun dominantContentHeavyApp(context: AnomalyDetectionContext): AppTrafficWindow? {
        val windowTotal = context.current.totalBytes.coerceAtLeast(1L)
        val windowUploadRatio = context.current.txBytes.toDouble() / windowTotal.toDouble()
        if (windowUploadRatio >= CONTENT_HEAVY_UPLOAD_RATIO_GATE) {
            return null
        }
        val dominant = context.appWindows.maxByOrNull(AppTrafficWindow::totalBytes) ?: return null
        val dominantShare = dominant.totalBytes.toDouble() / windowTotal.toDouble()
        return dominant.takeIf {
            dominantShare >= DOMINANT_APP_SHARE_GATE &&
                context.categoryOf(dominant.packageName) == AppNetworkUsageCategory.CONTENT_HEAVY
        }
    }
}

class NewDestinationCountryDetector : AnomalyDetector {
    override fun detect(context: AnomalyDetectionContext): List<AnomalySignal> {
        if (!context.settings.analyzeDestinationCountries ||
            context.history.learnedTrafficWindowCount < LEARNING_SAMPLES
        ) {
            return emptyList()
        }
        val knownCountries = context.history.effectiveSeenCountries
        val totalBytes = context.current.totalBytes.coerceAtLeast(1L)
        return context.current.destinationCountries.mapNotNull { (country, bytes) ->
            val normalizedCountry = country.uppercase(Locale.US)
            if (normalizedCountry in knownCountries || bytes < NEW_COUNTRY_MIN_BYTES) {
                return@mapNotNull null
            }
            val share = bytes.toDouble() / totalBytes.toDouble()
            val severity =
                when {
                    share >= 0.35 -> 0.45
                    share >= 0.18 -> 0.38
                    share >= 0.08 -> 0.30
                    else -> 0.0
                }
            severity
                .takeIf { it > 0.0 }
                ?.let {
                    AnomalySignal(
                        type = AnomalyType.NEW_DESTINATION_COUNTRY,
                        severity = it,
                        reason = "Traffic reached a new destination country",
                        evidence =
                        mapOf(
                            "country" to normalizedCountry,
                            "bytes" to bytes.toString(),
                            "traffic_share" to "%.2f".format(Locale.US, share),
                        ),
                    )
                }
        }
    }
}

class DnsBlockRatioSpikeDetector : AnomalyDetector {
    override fun detect(context: AnomalyDetectionContext): List<AnomalySignal> {
        val currentRatio = context.current.blockedDnsRatio()
        if (context.current.blockedDns + context.current.allowedDns < MIN_DNS_SAMPLES || currentRatio < 0.20) {
            return emptyList()
        }
        val history = context.history.trafficWindows.map { it.blockedDnsRatio() }
        val z = RobustStats.robustZ(currentRatio * 100.0, history.map { it * 100.0 })
        val baseline = history.averageOrNull() ?: 0.0
        if (z < context.settings.zScoreThreshold() && currentRatio - baseline < 0.25) {
            return emptyList()
        }
        val severity =
            maxOf(
                ((z - context.settings.zScoreThreshold()) / 6.0).coerceIn(0.0, 1.0),
                (currentRatio - baseline).coerceIn(0.0, 1.0)
            )
        return listOf(
            AnomalySignal(
                type = AnomalyType.DNS_BLOCK_RATIO_SPIKE,
                severity = severity.coerceIn(0.0, 1.0),
                reason = "DNS filter blocked more requests than usual",
                evidence =
                mapOf(
                    "blocked_ratio" to "%.2f".format(Locale.US, currentRatio),
                    "baseline_ratio" to "%.2f".format(Locale.US, baseline),
                    "blocked_dns" to context.current.blockedDns.toString(),
                    "allowed_dns" to context.current.allowedDns.toString(),
                ),
            ),
        )
    }
}

class ReconnectStormDetector : AnomalyDetector {
    override fun detect(context: AnomalyDetectionContext): List<AnomalySignal> {
        if (context.current.reconnects <= 0) {
            return emptyList()
        }
        val history = context.history.trafficWindows.map { it.reconnects.toDouble() }
        val z = RobustStats.robustZ(context.current.reconnects.toDouble(), history, minSamples = 6)
        val severity =
            when {
                context.current.reconnects >= 4 -> 0.72
                z >= context.settings.zScoreThreshold() -> ((z - context.settings.zScoreThreshold()) / 4.0).coerceIn(
                    0.35,
                    0.85
                )
                else -> 0.0
            }
        if (severity <= 0.0) {
            return emptyList()
        }
        return listOf(
            AnomalySignal(
                type = AnomalyType.RECONNECT_STORM,
                severity = severity,
                reason = "VPN reconnected several times in a short period",
                evidence =
                mapOf(
                    "reconnects" to context.current.reconnects.toString(),
                    "robust_z" to "%.2f".format(Locale.US, z),
                ),
            ),
        )
    }
}

class LatencyShiftDetector : AnomalyDetector {
    override fun detect(context: AnomalyDetectionContext): List<AnomalySignal> {
        val latency = context.current.latencyMs ?: return emptyList()
        val history = context.history.trafficWindows.mapNotNull { it.latencyMs?.toDouble() }
        val z = RobustStats.robustZ(latency.toDouble(), history)
        if (z < context.settings.zScoreThreshold()) {
            return emptyList()
        }
        return listOf(
            AnomalySignal(
                type = AnomalyType.LATENCY_SHIFT,
                severity = ((z - context.settings.zScoreThreshold()) / 6.0).coerceIn(0.0, 1.0),
                reason = "Tunnel latency shifted away from the local baseline",
                evidence =
                mapOf(
                    "latency_ms" to latency.toString(),
                    "robust_z" to "%.2f".format(Locale.US, z),
                ),
            ),
        )
    }
}

class DormantAppNetworkActivityDetector : AnomalyDetector {
    override fun detect(context: AnomalyDetectionContext): List<AnomalySignal> =
        context.appWindows.mapNotNull { appWindow -> detectForApp(context, appWindow) }

    private fun detectForApp(
        context: AnomalyDetectionContext,
        appWindow: AppTrafficWindow,
    ): AnomalySignal? {
        val presence = context.history.appPresence[appWindow.packageName] ?: return null
        if (appWindow.totalBytes < DormantMinBytes || presence.windowCount < DormantMinPresenceWindows) {
            return null
        }
        val idleMs = appWindow.startedAtMs - presence.lastSeenMs
        if (idleMs < DormantMinIdleMs) {
            return null
        }
        val uploadRatio = appWindow.txBytes.toDouble() / appWindow.totalBytes.coerceAtLeast(1L).toDouble()
        val idleDays = idleMs / DayMs
        var severity = DormantBaseSeverity
        if (appWindow.totalBytes >= DormantLargeBytes) {
            severity += 0.15
        }

        if (uploadRatio >= 0.45) {
            severity += 0.20
        }
        if (idleDays >= LongDormancyDays) {
            severity += 0.10
        }

        if (context.categoryOf(appWindow.packageName) == AppNetworkUsageCategory.CONTENT_HEAVY &&
            uploadRatio < CONTENT_HEAVY_UPLOAD_RATIO_GATE
        ) {
            severity -= 0.15
        }
        return AnomalySignal(
            type = AnomalyType.DORMANT_APP_NETWORK_ACTIVITY,
            severity = severity.coerceIn(0.0, DormantMaxSeverity),
            reason = "App was silent for a long time and suddenly used the network",
            evidence =
            mapOf(
                "package" to appWindow.packageName,
                "idle_days" to idleDays.toString(),
                "bytes" to appWindow.totalBytes.toString(),
                "upload_ratio" to "%.2f".format(Locale.US, uploadRatio),
            ),
        )
    }

    companion object {
        const val DormantMinIdleMs = 7L * 24L * 60L * 60L * 1000L
        const val DormantMinBytes = 256L * 1024L
        const val DormantLargeBytes = 5L * 1024L * 1024L
        const val DormantMinPresenceWindows = 4L
        const val DormantBaseSeverity = 0.45
        const val DormantMaxSeverity = 0.9
        const val LongDormancyDays = 30L
        private const val DayMs = 24L * 60L * 60L * 1000L
    }
}

internal fun robustZWithBaselineFallback(
    value: Double,
    history: List<Double>,
    baselineMedian: Double?,
    baselineMad: Double?,
    baselineSampleCount: Int,
): Double =
    when {
        history.size >= RobustStats.MIN_SAMPLES -> RobustStats.robustZ(value, history)
        baselineMedian != null && baselineMad != null && baselineSampleCount >= RobustStats.MIN_SAMPLES ->
            RobustStats.robustZFromStats(value, baselineMedian, baselineMad)
        else -> 0.0
    }

private fun AnomalySettings.zScoreThreshold(): Double =
    when (sensitivity) {
        AnomalySensitivity.NORMAL -> 3.5
        AnomalySensitivity.STRICT -> 3.0
    }

private fun Long.perMinute(durationSec: Int): Double =
    toDouble() / (durationSec.coerceAtLeast(1).toDouble() / 60.0)

private fun TrafficWindow.blockedDnsRatio(): Double {
    val total = blockedDns + allowedDns
    return if (total <= 0) 0.0 else blockedDns.toDouble() / total.toDouble()
}

private fun List<Double>.averageOrNull(): Double? =
    if (isEmpty()) null else average()

private const val LEARNING_SAMPLES = 12
private const val MIN_DNS_SAMPLES = 10
private const val NEW_COUNTRY_MIN_BYTES = 384L * 1024L
private const val CONTENT_HEAVY_UPLOAD_RATIO_GATE = 0.25
private const val CONTENT_HEAVY_DAMPEN_FACTOR = 0.35
private const val DOMINANT_APP_SHARE_GATE = 0.5
