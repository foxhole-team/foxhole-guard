package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AppNetworkUsageCategory
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.TrafficWindow

/**
 * Public entry point of FOXHOLE SENTINEL's traffic-anomaly detection.
 *
 * The app integration layer depends on this stable surface instead of the detector pipeline
 * internals (`AnomalyEngine`, the individual `AnomalyDetector`s). Today it wraps the on-device
 * traffic-anomaly engine and the installed-app risk scorer; a future updatable threat-intel feed
 * (installed-app signatures) plugs into the scorer so callers keep a single security-core API.
 */
class FoxholeSentinel(
    private val engine: AnomalyEngine = AnomalyEngine(),
) {
    fun evaluateTraffic(
        current: TrafficWindow,
        appWindows: List<AppTrafficWindow>,
        history: AnomalyHistory,
        settings: AnomalySettings = AnomalySettings(),
        appCategories: Map<String, AppNetworkUsageCategory> = emptyMap(),
    ): AnomalyAssessment = engine.evaluate(current, appWindows, history, settings, appCategories)

    /**
     * Score a single installed package's risk from Android-collected [facts], optionally matched
     * against an updatable [threatIntel] feed. The app-side collector gathers the facts; the
     * capability heuristics and threat-intel matching live here in the security core.
     */
    fun assessInstalledApp(
        facts: InstalledAppFacts,
        threatIntel: InstalledAppThreatIntel = InstalledAppThreatIntel.EMPTY,
    ): InstalledAppRiskAssessment = scoreInstalledApp(facts, threatIntel)
}
