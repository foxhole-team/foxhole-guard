package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AppNetworkUsageCategory
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.TrafficWindow

/**
 * Public entry point of FoxHole Sentinel's traffic-anomaly detection.
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

    /**
     * Match the destinations of observed flows against the bundle's known-bad hosts.
     *
     * Each event carries the packages that opened the flow, so a hit names an app rather than the
     * device — the one thing an on-device matcher can say that a static list cannot. A hit means
     * the destination is on a published indicator list, and no hit means nothing at all: both
     * upstream datasets state that absence of a match is not evidence of a clean device.
     *
     * Returns one finding per (event, package) pair so the caller can attribute without re-walking
     * the events. An empty bundle yields nothing and costs one flag check.
     */
    fun matchNetworkIndicators(
        events: List<NetworkActivityEvent>,
        matcher: NetworkIocMatcher,
    ): List<NetworkIocFinding> {
        if (matcher.isEmpty || events.isEmpty()) {
            return emptyList()
        }
        return events.flatMap { event ->
            val hit = matcher.match(event.remoteHost) ?: return@flatMap emptyList()
            event.packageNames.map { packageName ->
                NetworkIocFinding(
                    packageName = packageName,
                    remoteHost = event.remoteHost,
                    hit = hit,
                    timestampMs = event.timestampMs,
                    profileId = event.profileId,
                    protocol = event.protocol,
                )
            }
        }
    }
}

/**
 * One destination match, attributed to the package whose flow reached it. Profile and protocol are
 * carried over from the source event so a journal entry can attribute without re-walking events.
 */
data class NetworkIocFinding(
    val packageName: String,
    val remoteHost: String,
    val hit: NetworkIocHit,
    val timestampMs: Long,
    val profileId: Long?,
    val protocol: String,
)
