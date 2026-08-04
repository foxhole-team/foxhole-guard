package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.AnomalySensitivity
import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.AppBaseline
import com.foxhole.core.model.AppNetworkPresence
import com.foxhole.core.model.AppNetworkUsageCategory
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.NetworkType
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.VpnMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyEngineTest {
    private val engine = AnomalyEngine()
    private val enabledSettings =
        AnomalySettings(
            enabled = true,
            notifyUnusualTraffic = true,
            analyzeBackgroundTraffic = true,
            analyzeDestinationCountries = true,
        )

    @Test
    fun `disabled anomaly analysis stays silent`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 1_000_000, txBytes = 9_000_000),
                appWindows = listOf(appWindow(packageName = "com.chat", txBytes = 8_500_000, foreground = true)),
                history = AnomalyHistory(appWindowsByPackage = mapOf("com.chat" to appHistory(txBytes = 120_000))),
            )

        assertEquals(AnomalySeverity.SILENT, assessment.severity)
        assertTrue(assessment.signals.isEmpty())
    }

    @Test
    fun `does not alert during first learning period unless extreme anomaly`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(txBytes = 18_000_000),
                appWindows = listOf(appWindow(packageName = "com.test", txBytes = 17_000_000, foreground = false)),
                history = AnomalyHistory(),
                settings = enabledSettings,
            )

        assertEquals(AnomalySeverity.SILENT, assessment.severity)
        assertTrue(assessment.signals.isEmpty())
    }

    @Test
    fun `upload spike triggers app upload signal after baseline`() {
        val history = appHistory(txBytes = 120_000)
        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 1_000_000, txBytes = 9_000_000),
                appWindows = listOf(appWindow(packageName = "com.chat", txBytes = 8_500_000, foreground = true)),
                history = AnomalyHistory(appWindowsByPackage = mapOf("com.chat" to history)),
                settings = enabledSettings,
            )

        assertTrue(assessment.signals.any { it.type == AnomalyType.APP_UPLOAD_SPIKE })
    }

    @Test
    fun `background traffic triggers app background signal`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 1_000_000, txBytes = 7_000_000),
                appWindows = listOf(
                    appWindow(packageName = "com.sync", rxBytes = 500_000, txBytes = 6_500_000, foreground = false)
                ),
                history = AnomalyHistory(appWindowsByPackage = mapOf("com.sync" to appHistory(txBytes = 200_000))),
                settings = enabledSettings,
            )

        assertTrue(assessment.signals.any { it.type == AnomalyType.APP_BACKGROUND_TRAFFIC })
    }

    @Test
    fun `new country with tiny traffic does not notify`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(destinationCountries = mapOf("NL" to 64_000L)),
                appWindows = emptyList(),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
                settings = enabledSettings,
            )

        assertFalse(assessment.signals.any { it.type == AnomalyType.NEW_DESTINATION_COUNTRY })
        assertEquals(AnomalySeverity.SILENT, assessment.severity)
    }

    @Test
    fun `new country with high traffic logs activity without notification`() {
        val assessment =
            engine.evaluate(
                current =
                trafficWindow(
                    rxBytes = 1_000_000,
                    txBytes = 1_000_000,
                    destinationCountries = mapOf("BR" to 800_000L),
                ),
                appWindows = emptyList(),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
                settings = enabledSettings,
            )

        assertTrue(assessment.signals.any { it.type == AnomalyType.NEW_DESTINATION_COUNTRY })
        assertEquals(AnomalySeverity.ACTIVITY_LOG, assessment.severity)
        assertFalse(assessment.shouldNotify)
    }

    @Test
    fun `stationary heavy uploader at its own baseline stays silent`() {
        // A backup app that ALWAYS uploads a lot: current tx equals its historical baseline, so
        // z is ~0 and the share boost must not fire on share/ratio alone.
        val steadyTx = 6_000_000L
        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 1_000_000, txBytes = steadyTx),
                appWindows = listOf(appWindow(packageName = "com.backup", txBytes = steadyTx, foreground = true)),
                history =
                AnomalyHistory(
                    appWindowsByPackage = mapOf(
                        "com.backup" to appHistory(txBytes = steadyTx, packageName = "com.backup")
                    ),
                ),
                settings = enabledSettings,
            )

        assertFalse(assessment.signals.any { it.type == AnomalyType.APP_UPLOAD_SPIKE })
        assertEquals(AnomalySeverity.SILENT, assessment.severity)
    }

    @Test
    fun `strict sensitivity lowers score thresholds`() {
        assertEquals(AnomalySeverity.SILENT, AnomalyEngine.severityForScore(35, AnomalySensitivity.NORMAL))
        assertEquals(AnomalySeverity.ACTIVITY_LOG, AnomalyEngine.severityForScore(35, AnomalySensitivity.STRICT))
    }

    @Test
    fun `attention level anomaly logs without system notification by default`() {
        val assessment =
            AnomalyAssessment(
                signals = emptyList(),
                score = 75,
                severity = AnomalySeverity.NOTIFICATION,
            )

        assertTrue(assessment.shouldLogActivity)
        assertFalse(assessment.shouldNotify)
    }

    @Test
    fun `warning level anomaly can show system notification`() {
        val assessment =
            AnomalyAssessment(
                signals = emptyList(),
                score = 90,
                severity = AnomalySeverity.HIGH,
            )

        assertTrue(assessment.shouldNotify)
    }

    @Test
    fun `baseline history is expected to exclude current anomaly window`() {
        val baselineOnlyHistory = appHistory(txBytes = 100_000)
        val contaminatedHistory = baselineOnlyHistory + appWindow(packageName = "com.cloud", txBytes = 8_000_000)

        val clean =
            engine.evaluate(
                current = trafficWindow(rxBytes = 2_000_000, txBytes = 8_000_000),
                appWindows = listOf(appWindow(packageName = "com.cloud", txBytes = 8_000_000)),
                history = AnomalyHistory(appWindowsByPackage = mapOf("com.cloud" to baselineOnlyHistory)),
                settings = enabledSettings,
            )
        val contaminated =
            engine.evaluate(
                current = trafficWindow(rxBytes = 2_000_000, txBytes = 8_000_000),
                appWindows = listOf(appWindow(packageName = "com.cloud", txBytes = 8_000_000)),
                history = AnomalyHistory(appWindowsByPackage = mapOf("com.cloud" to contaminatedHistory)),
                settings = enabledSettings,
            )

        assertTrue(clean.score >= contaminated.score)
        assertTrue(clean.signals.any { it.type == AnomalyType.APP_UPLOAD_SPIKE })
    }

    @Test
    fun `combine signals saturates instead of summing`() {
        val score =
            AnomalyEngine.combineSignals(
                listOf(
                    com.foxhole.core.model.AnomalySignal(AnomalyType.APP_UPLOAD_SPIKE, 0.60, "a", emptyMap()),
                    com.foxhole.core.model.AnomalySignal(AnomalyType.NEW_DESTINATION_COUNTRY, 0.60, "b", emptyMap()),
                ),
            )

        assertEquals(84, score)
    }

    @Test
    fun `total traffic spike fires against a stable baseline`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 90_000_000, txBytes = 2_000_000),
                appWindows = emptyList(),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
                settings = enabledSettings,
            )

        assertTrue(assessment.signals.any { it.type == AnomalyType.TOTAL_TRAFFIC_SPIKE })
        assertTrue(assessment.shouldLogActivity)
    }

    @Test
    fun `total traffic spike is dampened when a content-heavy app dominates a download window`() {
        val history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE"))
        val spike = trafficWindow(rxBytes = 90_000_000, txBytes = 1_000_000)
        val streamingWindows = listOf(appWindow(packageName = "com.video", rxBytes = 70_000_000, txBytes = 900_000))

        val dampened =
            engine.evaluate(
                current = spike,
                appWindows = streamingWindows,
                history = history,
                settings = enabledSettings,
                appCategories = mapOf("com.video" to AppNetworkUsageCategory.CONTENT_HEAVY),
            )
        val undampened =
            engine.evaluate(
                current = spike,
                appWindows = streamingWindows,
                history = history,
                settings = enabledSettings,
            )

        val dampenedSignal = dampened.signals.first { it.type == AnomalyType.TOTAL_TRAFFIC_SPIKE }
        val undampenedSignal = undampened.signals.first { it.type == AnomalyType.TOTAL_TRAFFIC_SPIKE }
        assertTrue(dampenedSignal.severity < undampenedSignal.severity)
        assertEquals("true", dampenedSignal.evidence["content_dampened"])
        assertEquals("com.video", dampenedSignal.evidence["dominant_package"])
        assertNull(undampenedSignal.evidence["content_dampened"])
    }

    @Test
    fun `total traffic spike keeps full severity for upload-heavy windows even from content apps`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 2_000_000, txBytes = 90_000_000),
                appWindows = listOf(appWindow(packageName = "com.video", rxBytes = 1_000_000, txBytes = 80_000_000)),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
                settings = enabledSettings,
                appCategories = mapOf("com.video" to AppNetworkUsageCategory.CONTENT_HEAVY),
            )

        val signal = assessment.signals.first { it.type == AnomalyType.TOTAL_TRAFFIC_SPIKE }
        assertNull(signal.evidence["content_dampened"])
    }

    @Test
    fun `content-heavy app ack-shaped upload spike is softer than a standard app's`() {
        // Streaming inflates tx through acks while staying download-shaped (low upload ratio).
        val history = appHistory(txBytes = 50_000)
        val currentWindow = trafficWindow(rxBytes = 80_000_000, txBytes = 1_500_000)
        val ackWindows = listOf(appWindow(packageName = "com.chat", rxBytes = 60_000_000, txBytes = 1_200_000))

        val standard =
            engine.evaluate(
                current = currentWindow,
                appWindows = ackWindows,
                history = AnomalyHistory(appWindowsByPackage = mapOf("com.chat" to history)),
                settings = enabledSettings,
            )
        val contentHeavy =
            engine.evaluate(
                current = currentWindow,
                appWindows = ackWindows,
                history = AnomalyHistory(appWindowsByPackage = mapOf("com.chat" to history)),
                settings = enabledSettings,
                appCategories = mapOf("com.chat" to AppNetworkUsageCategory.CONTENT_HEAVY),
            )

        val standardSignal = standard.signals.first { it.type == AnomalyType.APP_UPLOAD_SPIKE }
        val softSignal = contentHeavy.signals.first { it.type == AnomalyType.APP_UPLOAD_SPIKE }
        assertTrue(softSignal.severity < standardSignal.severity)
        assertEquals("true", softSignal.evidence["content_dampened"])
    }

    @Test
    fun `dns block ratio spike fires when blocks jump above a quiet baseline`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(blockedDns = 60, allowedDns = 40),
                appWindows = emptyList(),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
                settings = enabledSettings,
            )

        assertTrue(assessment.signals.any { it.type == AnomalyType.DNS_BLOCK_RATIO_SPIKE })
    }

    @Test
    fun `dns block ratio stays silent under the minimum sample count`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(blockedDns = 4, allowedDns = 2),
                appWindows = emptyList(),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
                settings = enabledSettings,
            )

        assertFalse(assessment.signals.any { it.type == AnomalyType.DNS_BLOCK_RATIO_SPIKE })
    }

    @Test
    fun `four reconnects in one window always signal a storm`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(reconnects = 4),
                appWindows = emptyList(),
                history = AnomalyHistory(),
                settings = enabledSettings,
            )

        val signal = assessment.signals.first { it.type == AnomalyType.RECONNECT_STORM }
        assertEquals(0.72, signal.severity, 1e-9)
    }

    @Test
    fun `zero reconnects never signal a storm`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(reconnects = 0),
                appWindows = emptyList(),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
                settings = enabledSettings,
            )

        assertFalse(assessment.signals.any { it.type == AnomalyType.RECONNECT_STORM })
    }

    @Test
    fun `latency shift fires against a stable latency history`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(latencyMs = 900),
                appWindows = emptyList(),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
                settings = enabledSettings,
            )

        assertTrue(assessment.signals.any { it.type == AnomalyType.LATENCY_SHIFT })
    }

    @Test
    fun `latency shift stays silent without a latency reading`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(latencyMs = null),
                appWindows = emptyList(),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
                settings = enabledSettings,
            )

        assertFalse(assessment.signals.any { it.type == AnomalyType.LATENCY_SHIFT })
    }

    @Test
    fun `dormant app suddenly using the network is flagged`() {
        val windowStart = 1_000_000L
        val assessment =
            engine.evaluate(
                current = trafficWindow(),
                appWindows =
                listOf(
                    appWindow(
                        packageName = "com.sleeper",
                        rxBytes = 200_000,
                        txBytes = 400_000,
                        startedAtMs = windowStart
                    ),
                ),
                history =
                AnomalyHistory(
                    appPresence =
                    mapOf(
                        "com.sleeper" to
                            AppNetworkPresence(
                                packageName = "com.sleeper",
                                firstSeenMs = 0L,
                                lastSeenMs = windowStart - 10L * DAY_MS,
                                windowCount = 20,
                            ),
                    ),
                ),
                settings = enabledSettings,
            )

        val signal = assessment.signals.first { it.type == AnomalyType.DORMANT_APP_NETWORK_ACTIVITY }
        assertEquals("10", signal.evidence["idle_days"])
        assertTrue(assessment.shouldLogActivity)
    }

    @Test
    fun `dormant app that wakes up to upload escalates severity`() {
        val windowStart = 1_000_000L
        val presence =
            mapOf(
                "com.sleeper" to
                    AppNetworkPresence(
                        packageName = "com.sleeper",
                        firstSeenMs = 0L,
                        lastSeenMs = windowStart - 10L * DAY_MS,
                        windowCount = 20,
                    ),
            )

        val downloadWake =
            engine.evaluate(
                current = trafficWindow(),
                appWindows = listOf(
                    appWindow(
                        packageName = "com.sleeper",
                        rxBytes = 500_000,
                        txBytes = 10_000,
                        startedAtMs = windowStart
                    )
                ),
                history = AnomalyHistory(appPresence = presence),
                settings = enabledSettings,
            ).signals.first { it.type == AnomalyType.DORMANT_APP_NETWORK_ACTIVITY }
        val uploadWake =
            engine.evaluate(
                current = trafficWindow(),
                appWindows = listOf(
                    appWindow(
                        packageName = "com.sleeper",
                        rxBytes = 10_000,
                        txBytes = 500_000,
                        startedAtMs = windowStart
                    )
                ),
                history = AnomalyHistory(appPresence = presence),
                settings = enabledSettings,
            ).signals.first { it.type == AnomalyType.DORMANT_APP_NETWORK_ACTIVITY }

        assertTrue(uploadWake.severity > downloadWake.severity)
    }

    @Test
    fun `recently active or unknown apps never read as dormant`() {
        val windowStart = 1_000_000L
        val assessment =
            engine.evaluate(
                current = trafficWindow(),
                appWindows =
                listOf(
                    appWindow(
                        packageName = "com.active",
                        rxBytes = 300_000,
                        txBytes = 300_000,
                        startedAtMs = windowStart
                    ),
                    appWindow(
                        packageName = "com.brand.new",
                        rxBytes = 300_000,
                        txBytes = 300_000,
                        startedAtMs = windowStart
                    ),
                ),
                history =
                AnomalyHistory(
                    appPresence =
                    mapOf(
                        "com.active" to
                            AppNetworkPresence(
                                packageName = "com.active",
                                firstSeenMs = 0L,
                                lastSeenMs = windowStart - DAY_MS,
                                windowCount = 20,
                            ),
                    ),
                ),
                settings = enabledSettings,
            )

        assertFalse(assessment.signals.any { it.type == AnomalyType.DORMANT_APP_NETWORK_ACTIVITY })
    }

    @Test
    fun `persisted seen countries suppress re-alerting after raw history expires`() {
        // Raw windows only know DE, but the long-horizon store also remembers US.
        val assessment =
            engine.evaluate(
                current =
                trafficWindow(
                    rxBytes = 1_000_000,
                    txBytes = 1_000_000,
                    destinationCountries = mapOf("US" to 800_000L),
                ),
                appWindows = emptyList(),
                history =
                AnomalyHistory(
                    trafficWindows = trafficHistory(country = "DE"),
                    everSeenCountries = setOf("DE", "US"),
                ),
                settings = enabledSettings,
            )

        assertFalse(assessment.signals.any { it.type == AnomalyType.NEW_DESTINATION_COUNTRY })
    }

    @Test
    fun `persisted window count keeps novelty armed when raw history was cleaned up`() {
        val assessment =
            engine.evaluate(
                current =
                trafficWindow(
                    rxBytes = 1_000_000,
                    txBytes = 1_000_000,
                    destinationCountries = mapOf("BR" to 800_000L),
                ),
                appWindows = emptyList(),
                history =
                AnomalyHistory(
                    trafficWindows = emptyList(),
                    everSeenCountries = setOf("DE"),
                    observedTrafficWindowCount = 200L,
                ),
                settings = enabledSettings,
            )

        assertTrue(assessment.signals.any { it.type == AnomalyType.NEW_DESTINATION_COUNTRY })
    }

    @Test
    fun `persisted app baseline keeps upload detection armed without raw history`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 1_000_000, txBytes = 9_000_000),
                appWindows = listOf(appWindow(packageName = "com.chat", txBytes = 8_500_000, foreground = true)),
                history =
                AnomalyHistory(
                    appBaselinesByPackage =
                    mapOf(
                        "com.chat" to
                            AppBaseline(
                                key = "app|com.chat",
                                packageName = "com.chat",
                                profileId = "1",
                                protocol = "vless",
                                networkType = NetworkType.WIFI,
                                hourBucket = 12,
                                metric = "app_tx_bytes_per_min",
                                median = 120_000.0,
                                mad = 5_000.0,
                                ewma = 120_000.0,
                                ewmad = 5_000.0,
                                sampleCount = 96,
                                lastUpdatedAt = 0L,
                            ),
                    ),
                ),
                settings = enabledSettings,
            )

        assertNotNull(assessment.signals.firstOrNull { it.type == AnomalyType.APP_UPLOAD_SPIKE })
    }

    @Test
    fun `excluded package produces no per-app signals while others still fire`() {
        val settings = enabledSettings.copy(excludedPackages = listOf("com.chat"))
        val history =
            AnomalyHistory(
                appWindowsByPackage =
                mapOf(
                    "com.chat" to appHistory(txBytes = 120_000),
                    "com.other" to appHistory(txBytes = 120_000, packageName = "com.other"),
                ),
            )
        val appWindows =
            listOf(
                appWindow(packageName = "com.chat", txBytes = 8_500_000, foreground = true),
                appWindow(packageName = "com.other", txBytes = 8_500_000, foreground = true),
            )

        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 1_000_000, txBytes = 17_000_000),
                appWindows = appWindows,
                history = history,
                settings = settings,
            )

        val uploadSignals = assessment.signals.filter { it.type == AnomalyType.APP_UPLOAD_SPIKE }
        assertTrue(uploadSignals.isNotEmpty())
        assertTrue(uploadSignals.none { it.evidence["package"] == "com.chat" })
        assertTrue(uploadSignals.any { it.evidence["package"] == "com.other" })
    }

    @Test
    fun `excluded dormant app never reads as dormant`() {
        val windowStart = 1_000_000L
        val assessment =
            engine.evaluate(
                current = trafficWindow(),
                appWindows =
                listOf(
                    appWindow(
                        packageName = "com.sleeper",
                        rxBytes = 200_000,
                        txBytes = 400_000,
                        startedAtMs = windowStart
                    ),
                ),
                history =
                AnomalyHistory(
                    appPresence =
                    mapOf(
                        "com.sleeper" to
                            AppNetworkPresence(
                                packageName = "com.sleeper",
                                firstSeenMs = 0L,
                                lastSeenMs = windowStart - 10L * DAY_MS,
                                windowCount = 20,
                            ),
                    ),
                ),
                settings = enabledSettings.copy(excludedPackages = listOf("com.sleeper")),
            )

        assertFalse(assessment.signals.any { it.type == AnomalyType.DORMANT_APP_NETWORK_ACTIVITY })
    }

    @Test
    fun `total traffic spike still fires when the dominant app is excluded`() {
        // Full exclusion removes the excluded app's windows entirely, so the total spike keeps
        // firing on `current` and intentionally loses the content-heavy dampening.
        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 90_000_000, txBytes = 1_000_000),
                appWindows = listOf(appWindow(packageName = "com.video", rxBytes = 70_000_000, txBytes = 900_000)),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
                settings = enabledSettings.copy(excludedPackages = listOf("com.video")),
                appCategories = mapOf("com.video" to AppNetworkUsageCategory.CONTENT_HEAVY),
            )

        val signal = assessment.signals.first { it.type == AnomalyType.TOTAL_TRAFFIC_SPIKE }
        assertNull(signal.evidence["content_dampened"])
        assertNull(signal.evidence["dominant_package"])
    }

    private fun trafficWindow(
        rxBytes: Long = 1_000_000,
        txBytes: Long = 1_000_000,
        destinationCountries: Map<String, Long> = mapOf("DE" to rxBytes + txBytes),
        blockedDns: Int = 0,
        allowedDns: Int = 0,
        reconnects: Int = 0,
        latencyMs: Int? = 100,
    ): TrafficWindow =
        TrafficWindow(
            startedAtMs = 1_000_000,
            durationSec = 60,
            networkType = NetworkType.WIFI,
            vpnMode = VpnMode.NORMAL,
            profileId = "1",
            protocol = "vless",
            rxBytes = rxBytes,
            txBytes = txBytes,
            blockedDns = blockedDns,
            allowedDns = allowedDns,
            reconnects = reconnects,
            latencyMs = latencyMs,
            destinationCountries = destinationCountries,
        )

    private fun appWindow(
        packageName: String,
        rxBytes: Long = 0,
        txBytes: Long,
        foreground: Boolean? = true,
        startedAtMs: Long = 1_000_000,
    ): AppTrafficWindow =
        AppTrafficWindow(
            packageName = packageName,
            startedAtMs = startedAtMs,
            durationSec = 60,
            rxBytes = rxBytes,
            txBytes = txBytes,
            foreground = foreground,
            networkType = NetworkType.WIFI,
            uid = 42,
        )

    private fun appHistory(
        txBytes: Long,
        packageName: String = "com.chat",
    ): List<AppTrafficWindow> =
        (1..14).map { index ->
            appWindow(
                packageName = packageName,
                txBytes = txBytes + index,
                foreground = true,
            )
        }

    private fun trafficHistory(country: String): List<TrafficWindow> =
        (1..14).map { index ->
            trafficWindow(destinationCountries = mapOf(country to 1_000_000L + index))
        }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
