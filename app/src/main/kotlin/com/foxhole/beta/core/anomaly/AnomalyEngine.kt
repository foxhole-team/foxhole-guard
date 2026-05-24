package com.foxhole.beta.core.anomaly

import com.foxhole.beta.core.model.AnomalySensitivity
import com.foxhole.beta.core.model.AnomalySeverity
import com.foxhole.beta.core.model.AnomalySettings
import com.foxhole.beta.core.model.AnomalySignal
import com.foxhole.beta.core.model.AnomalyType
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.model.VpnMode
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
) {
    val knownDestinationCountries: Set<String> =
        trafficWindows
            .flatMap { window -> window.destinationCountries.keys }
            .mapTo(linkedSetOf()) { country -> country.uppercase(Locale.US) }
}

class AnomalyEngine(
    private val detectors: List<AnomalyDetector> = defaultDetectors(),
) {
    fun evaluate(
        current: TrafficWindow,
        appWindows: List<AppTrafficWindow>,
        history: AnomalyHistory,
        settings: AnomalySettings = AnomalySettings(),
    ): AnomalyAssessment {
        if (!settings.enabled) {
            return AnomalyAssessment(
                signals = emptyList(),
                score = 0,
                severity = AnomalySeverity.SILENT,
            )
        }
        val context =
            AnomalyDetectionContext(
                current = current,
                appWindows = appWindows,
                history = history,
                settings = settings,
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
)

private fun defaultDetectors(): List<AnomalyDetector> =
    listOf(
        AppUploadSpikeDetector(),
        AppBackgroundTrafficDetector(),
        TotalTrafficSpikeDetector(),
        NewDestinationCountryDetector(),
        DnsBlockRatioSpikeDetector(),
        ReconnectStormDetector(),
        LatencyShiftDetector(),
        TorI2pRouteMismatchDetector(),
    )

class AppUploadSpikeDetector : AnomalyDetector {
    override fun detect(context: AnomalyDetectionContext): List<AnomalySignal> {
        val zThreshold = context.settings.zScoreThreshold()
        val totalBytes = context.current.totalBytes.coerceAtLeast(1L)
        return context.appWindows.mapNotNull { appWindow ->
            val history =
                context.history.appWindowsByPackage[appWindow.packageName]
                    .orEmpty()
                    .map { window -> window.txBytes.perMinute(window.durationSec) }
            val txPerMin = appWindow.txBytes.perMinute(appWindow.durationSec)
            val z = RobustStats.robustZ(txPerMin, history)
            val appShare = appWindow.totalBytes.toDouble() / totalBytes.toDouble()
            val uploadRatio = appWindow.txBytes.toDouble() / appWindow.totalBytes.coerceAtLeast(1L).toDouble()
            val shareBoost =
                if (appShare >= 0.55 && uploadRatio >= 0.55 && history.size >= LearningSamples) {
                    0.28
                } else {
                    0.0
                }
            val severity = (((z - zThreshold) / 6.0).coerceIn(0.0, 1.0) + shareBoost).coerceIn(0.0, 1.0)
            if (severity <= 0.0) {
                null
            } else {
                AnomalySignal(
                    type = AnomalyType.APP_UPLOAD_SPIKE,
                    severity = severity,
                    reason = "App upload is much higher than its local baseline",
                    evidence =
                        mapOf(
                            "package" to appWindow.packageName,
                            "tx_per_min" to txPerMin.toLong().toString(),
                            "robust_z" to "%.2f".format(Locale.US, z),
                            "app_share" to "%.2f".format(Locale.US, appShare),
                            "upload_ratio" to "%.2f".format(Locale.US, uploadRatio),
                        ),
                )
            }
        }
    }
}

class AppBackgroundTrafficDetector : AnomalyDetector {
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
            if (history.size < LearningSamples) {
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
        val z = RobustStats.robustZ(totalPerMin, history)
        val zThreshold = context.settings.zScoreThreshold()
        if (z < zThreshold) {
            return emptyList()
        }
        val severity = ((z - zThreshold) / 6.0).coerceIn(0.0, 1.0)
        return listOf(
            AnomalySignal(
                type = AnomalyType.TOTAL_TRAFFIC_SPIKE,
                severity = severity,
                reason = "Total traffic is much higher than the local baseline",
                evidence =
                    mapOf(
                        "bytes_per_min" to totalPerMin.toLong().toString(),
                        "robust_z" to "%.2f".format(Locale.US, z),
                    ),
            ),
        )
    }
}

class NewDestinationCountryDetector : AnomalyDetector {
    override fun detect(context: AnomalyDetectionContext): List<AnomalySignal> {
        if (!context.settings.analyzeDestinationCountries || context.history.trafficWindows.size < LearningSamples) {
            return emptyList()
        }
        val knownCountries = context.history.knownDestinationCountries
        val totalBytes = context.current.totalBytes.coerceAtLeast(1L)
        return context.current.destinationCountries.mapNotNull { (country, bytes) ->
            val normalizedCountry = country.uppercase(Locale.US)
            if (normalizedCountry in knownCountries || bytes < NewCountryMinBytes) {
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
        if (context.current.blockedDns + context.current.allowedDns < MinDnsSamples || currentRatio < 0.20) {
            return emptyList()
        }
        val history = context.history.trafficWindows.map { it.blockedDnsRatio() }
        val z = RobustStats.robustZ(currentRatio * 100.0, history.map { it * 100.0 })
        val baseline = history.averageOrNull() ?: 0.0
        if (z < context.settings.zScoreThreshold() && currentRatio - baseline < 0.25) {
            return emptyList()
        }
        val severity = maxOf(((z - context.settings.zScoreThreshold()) / 6.0).coerceIn(0.0, 1.0), (currentRatio - baseline).coerceIn(0.0, 1.0))
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
                z >= context.settings.zScoreThreshold() -> ((z - context.settings.zScoreThreshold()) / 4.0).coerceIn(0.35, 0.85)
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

class TorI2pRouteMismatchDetector : AnomalyDetector {
    override fun detect(context: AnomalyDetectionContext): List<AnomalySignal> {
        val countryTraffic = context.current.destinationCountries
        if (
            context.history.trafficWindows.size < LearningSamples ||
            context.current.vpnMode == VpnMode.TOR ||
            context.current.vpnMode == VpnMode.I2P ||
            countryTraffic.isEmpty()
        ) {
            return emptyList()
        }
        val suspiciousShare =
            countryTraffic
                .filterKeys { country -> country.uppercase(Locale.US) in PrivacyRouteCountryHints }
                .values
                .sum()
                .toDouble() / context.current.totalBytes.coerceAtLeast(1L).toDouble()
        if (suspiciousShare < 0.40) {
            return emptyList()
        }
        return listOf(
            AnomalySignal(
                type = AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH,
                severity = suspiciousShare.coerceIn(0.0, 0.45),
                reason = "Traffic route changed while privacy routing is off",
                evidence = mapOf("route_share" to "%.2f".format(Locale.US, suspiciousShare)),
            ),
        )
    }

    private companion object {
        val PrivacyRouteCountryHints = setOf("NL", "DE", "FI", "SE", "CH")
    }
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

private const val LearningSamples = 12
private const val MinDnsSamples = 10
private const val NewCountryMinBytes = 384L * 1024L
