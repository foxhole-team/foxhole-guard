package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AppNetworkUsageCategory
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.TrafficWindow

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

    fun assessInstalledApp(
        facts: InstalledAppFacts,
        threatIntel: InstalledAppThreatIntel = InstalledAppThreatIntel.EMPTY,
    ): InstalledAppRiskAssessment = scoreInstalledApp(facts, threatIntel)

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

data class NetworkIocFinding(
    val packageName: String,
    val remoteHost: String,
    val hit: NetworkIocHit,
    val timestampMs: Long,
    val profileId: Long?,
    val protocol: String,
)
