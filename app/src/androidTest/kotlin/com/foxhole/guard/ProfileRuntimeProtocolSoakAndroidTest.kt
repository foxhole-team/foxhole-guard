package com.foxhole.guard

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.LatencyProbeMethod
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.storedProtocolHintOrNull
import com.foxhole.core.runtime.TunnelValidationEvidenceClassifier
import com.foxhole.guard.core.settings.updateI2pEnabled
import com.foxhole.guard.core.settings.updateI2pEngaged
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.core.settings.updateTrafficMapEnabled
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
internal class ProfileRuntimeProtocolSoakAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualSmartSubscriptionProtocolSoak() {
        assumeTrue(
            "live protocol soak skipped: pass -e $ARG_ENABLED 1 to run it",
            argument(ARG_ENABLED) == "1",
        )
        runBlocking {
            runProtocolSoak(ApplicationProvider.getApplicationContext())
        }
    }

    private suspend fun runProtocolSoak(app: FoxholeApplication) {
        val protocol = requiredProtocol()
        val subscription = requiredRawSubscription()
        val durationMs = positiveDurationArgument(ARG_DURATION_MS, DEFAULT_DURATION_MS)
        val probeIntervalMs =
            positiveDurationArgument(ARG_PROBE_INTERVAL_MS, DEFAULT_PROBE_INTERVAL_MS)
                .coerceAtMost(durationMs)
                .coerceAtMost(DEFAULT_PROBE_INTERVAL_MS)
        assertTrue("VPN permission was not granted for the live protocol soak", ensureVpnPermission(app))

        val settings = app.container.settingsRepository
        val previousSettings = settings.current()
        var primaryFailure: Throwable? = null
        try {
            val target = prepareTarget(app, subscription, protocol)
            val startedAt = connectAndOpenHome(app, target, protocol, durationMs, probeIntervalMs)
            val result = holdAndProbe(app, protocol, startedAt, durationMs, probeIntervalMs)
            assertSoakResult(result)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            val cleanupFailure =
                withContext(NonCancellable) {
                    runCatching { assertStoppedCleanly(app) }.exceptionOrNull()
                }
            val restoreFailure =
                withContext(NonCancellable) {
                    runCatching { restoreSettings(settings, previousSettings) }.exceptionOrNull()
                }
            val finalizationFailure = cleanupFailure ?: restoreFailure
            if (cleanupFailure != null && restoreFailure != null) {
                cleanupFailure.addSuppressed(restoreFailure)
            }
            primaryFailure?.let { original -> finalizationFailure?.let(original::addSuppressed) }
                ?: finalizationFailure?.let { throw it }
        }
    }

    private suspend fun prepareTarget(
        app: FoxholeApplication,
        subscription: String,
        protocol: ProtocolHint,
    ): PreparedTarget {
        assertStoppedCleanly(app)
        resetRelevantSettings(app)
        with(app.container.settingsRepository) {
            updatePrivacyRoutePermitted(false)
            updateI2pEngaged(false)
            updateI2pEnabled(false)
        }
        val isolatedSettings = app.container.settingsRepository.current()
        assertFalse("protocol soak must keep TOR permission disabled", isolatedSettings.privacyRoute.permitted)
        assertFalse("protocol soak must keep the TOR route disabled", isolatedSettings.privacyRoute.enabled)
        assertFalse("protocol soak must keep I2P disabled", isolatedSettings.i2p.enabled)
        assertFalse("protocol soak must keep I2P disengaged", isolatedSettings.i2p.engaged)
        clearProfiles(app)
        val imported =
            runCatching {
                app.container.profileRepository.importProfile(
                    rawInput = subscription,
                    preferredName = "Live Protocol Soak",
                    allowInsecureTlsForProfile = argument(ARG_ALLOW_INSECURE_TLS) == "1",
                )
            }.getOrElse { error ->
                throw AssertionError("subscription import failed: ${error.javaClass.simpleName}")
            }
        app.container.connectionController.setActiveProfile(imported.id)
        val matches =
            app.container.profileRepository.profiles
                .first()
                .flatMap { profile ->
                    profile
                        .runtimeProbeTargets()
                        .filter { target -> target.protocolHint == protocol }
                        .map { target -> PreparedTarget(profile.id, target.optionId) }
                }
        assertTrue("subscription did not expose protocol ${protocol.name}", matches.isNotEmpty())
        Log.d(TEST_TAG, "protocolSoak import protocol=${protocol.name.lowercase()} matchingTargets=${matches.size}")

        baselineRuntimeSettings(app)
        app.container.settingsRepository.updateLatencyProbeMethod(LatencyProbeMethod.TCP)
        app.container.settingsRepository.updateTrafficMapEnabled(true)
        return matches.first()
    }

    private suspend fun connectAndOpenHome(
        app: FoxholeApplication,
        target: PreparedTarget,
        protocol: ProtocolHint,
        durationMs: Long,
        probeIntervalMs: Long,
    ): Long {
        app.container.connectionController.setActiveProfile(target.profileId)
        val startedAt = System.currentTimeMillis()
        Log.d(
            TEST_TAG,
            "protocolSoak start protocol=${protocol.name.lowercase()} durationMs=$durationMs intervalMs=$probeIntervalMs",
        )
        runCatching {
            app.container.connectionController.connect(target.profileId, protocolOptionId = target.optionId)
        }.getOrElse { error ->
            throw AssertionError("protocol runtime start failed: ${error.javaClass.simpleName}")
        }
        val terminalState =
            withTimeoutOrNull(liveSmartTerminalTimeoutMs(protocol)) {
                waitForActiveConnectionAttempt(app)
                waitForTerminalState(app)
            }
        assertEquals("protocol did not reach CONNECTED", ConnectionState.CONNECTED, terminalState)
        assertTrue("initial VPN-bound IP refresh did not return a numeric address", hasSuccessfulIpProof(app))
        shell("am start -W -n ${app.packageName}/com.foxhole.guard.ui.cli.CliMainActivity >/dev/null")
        delay(UI_SETTLE_MS)
        Log.d(TEST_TAG, "protocolSoak connected protocol=${protocol.name.lowercase()} ipRefreshOk=true")
        return startedAt
    }

    private suspend fun holdAndProbe(
        app: FoxholeApplication,
        protocol: ProtocolHint,
        startedAt: Long,
        durationMs: Long,
        probeIntervalMs: Long,
    ): SoakResult {
        val failures = mutableListOf<String>()
        val resources = mutableListOf(captureResources())
        val initialTraffic = app.container.connectionController.traffic.value
        var previousRx = initialTraffic.rxTotalBytes
        var previousTx = initialTraffic.txTotalBytes
        var finalRx = previousRx
        var finalTx = previousTx
        var probes = 0
        var fatalRuntimeEvidence = false
        var successfulTunnelEvidence = false
        val ipProofTracker = IpProofTracker()
        val holdStartedAt = SystemClock.elapsedRealtime()
        val deadline = holdStartedAt + durationMs
        val minimumProbes = minimumProbeCount(durationMs, probeIntervalMs)
        var nextProbeAt = holdStartedAt + probeIntervalMs.coerceAtMost(durationMs)
        var deadlineCovered = false

        while (!deadlineCovered) {
            val waitMs = nextProbeAt - SystemClock.elapsedRealtime()
            if (waitMs > 0L) {
                delay(waitMs)
            }
            val probeAt = SystemClock.elapsedRealtime()
            deadlineCovered = probeAt >= deadline
            probes += 1
            val elapsedSec = (probeAt - holdStartedAt) / MILLIS_PER_SECOND
            val latencyMs =
                runCatching {
                    app.container.connectionController.measureCurrentConnectionLatency(timeoutMs = LATENCY_TIMEOUT_MS)
                }.getOrNull()
            val ipRefreshOk = hasSuccessfulIpProof(app)
            ipProofTracker.recordScheduled(ipRefreshOk)
            val snapshot = app.container.connectionController.snapshot.value
            val runtimePhase = app.container.connectionController.runtimeUiState.value.phase.runtimeStressLabel()
            val traffic = app.container.connectionController.traffic.value
            val serviceActive = hasFoxholeRuntimeServices(app)
            val vpnNetworkActive = hasActiveFoxholeVpnNetwork(app)
            val resource = captureResources()
            val evidence =
                TunnelValidationEvidenceClassifier.classify(
                    entries = app.container.diagnosticsLogger.entries.value,
                    sinceMs = startedAt,
                )
            fatalRuntimeEvidence = fatalRuntimeEvidence || evidence.fatalRuntimeMessage != null
            successfulTunnelEvidence = successfulTunnelEvidence || evidence.hasSuccessfulTunnelActivity
            resources += resource
            finalRx = traffic.rxTotalBytes
            finalTx = traffic.txTotalBytes

            if (snapshot.state != ConnectionState.CONNECTED) failures += "state@$elapsedSec=${snapshot.state.name}"
            if (snapshot.protocolHint != protocol) failures += "protocol@$elapsedSec=${snapshot.protocolHint?.name ?: "none"}"
            if (runtimePhase != RUNTIME_PHASE_CONNECTED) failures += "runtime@$elapsedSec=$runtimePhase"
            if (!serviceActive) failures += "service@$elapsedSec"
            if (!vpnNetworkActive) failures += "vpnNetwork@$elapsedSec"
            if (latencyMs == null || latencyMs < 0L) failures += "latency@$elapsedSec"
            if (!traffic.available) failures += "trafficUnavailable@$elapsedSec"
            if (finalRx < previousRx || finalTx < previousTx) failures += "trafficReset@$elapsedSec"
            previousRx = finalRx
            previousTx = finalTx

            Log.d(
                TEST_TAG,
                "protocolSoak tick protocol=${protocol.name.lowercase()} elapsedSec=$elapsedSec state=${snapshot.state.name} " +
                    "runtimePhase=$runtimePhase serviceActive=$serviceActive vpnNetworkActive=$vpnNetworkActive " +
                    "latencyOk=${latencyMs != null && latencyMs >= 0L} latencyMs=${latencyMs ?: -1L} " +
                    "ipRefreshOk=$ipRefreshOk scheduledIpFailures=${ipProofTracker.scheduledFailures} " +
                    "consecutiveIpFailures=${ipProofTracker.consecutiveFailures} " +
                    "rxTotal=$finalRx txTotal=$finalTx rssKb=${resource.rssKb ?: -1L} " +
                    "pssKb=${resource.pssKb ?: -1L} threads=${resource.threadCount} fds=${resource.fdCount}",
            )
            if (!deadlineCovered) {
                nextProbeAt = (nextProbeAt + probeIntervalMs).coerceAtMost(deadline)
            }
        }

        val finalIpProofOk = hasSuccessfulIpProof(app)
        ipProofTracker.recordFinal(finalIpProofOk)
        Log.d(
            TEST_TAG,
            "protocolSoak finalIpProof protocol=${protocol.name.lowercase()} finalIpProofOk=$finalIpProofOk " +
                "scheduledIpFailures=${ipProofTracker.scheduledFailures} " +
                "maxConsecutiveIpFailures=${ipProofTracker.maxConsecutiveFailures}",
        )
        val finalEvidence =
            TunnelValidationEvidenceClassifier.classify(
                entries = app.container.diagnosticsLogger.entries.value,
                sinceMs = startedAt,
            )
        return SoakResult(
            probes = probes,
            minimumProbes = minimumProbes,
            deadlineCovered = deadlineCovered,
            failures = failures,
            finalState = app.container.connectionController.snapshot.value.state,
            trafficGrew = finalRx + finalTx > initialTraffic.rxTotalBytes + initialTraffic.txTotalBytes,
            fatalRuntimeEvidence = fatalRuntimeEvidence || finalEvidence.fatalRuntimeMessage != null,
            successfulTunnelEvidence = successfulTunnelEvidence || finalEvidence.hasSuccessfulTunnelActivity,
            scheduledIpFailures = ipProofTracker.scheduledFailures,
            maxConsecutiveIpFailures = ipProofTracker.maxConsecutiveFailures,
            finalIpProofOk = finalIpProofOk,
            resources = resources,
        )
    }

    private fun assertSoakResult(result: SoakResult) {
        Log.d(
            TEST_TAG,
            "protocolSoak result probes=${result.probes} minimumProbes=${result.minimumProbes} " +
                "deadlineCovered=${result.deadlineCovered} finalState=${result.finalState.name} " +
                "failures=${result.failures.size} trafficGrew=${result.trafficGrew} " +
                "fatalRuntimeEvidence=${result.fatalRuntimeEvidence} " +
                "successfulTunnelEvidence=${result.successfulTunnelEvidence} " +
                "scheduledIpFailures=${result.scheduledIpFailures} " +
                "maxConsecutiveIpFailures=${result.maxConsecutiveIpFailures} " +
                "finalIpProofOk=${result.finalIpProofOk}",
        )
        assertTrue(
            "protocol soak executed ${result.probes}/${result.minimumProbes} required probes",
            result.probes >= result.minimumProbes,
        )
        assertTrue("protocol soak did not execute a full probe on or after its deadline", result.deadlineCovered)
        assertEquals(ConnectionState.CONNECTED, result.finalState)
        assertTrue("protocol soak failures: ${result.failures.joinToString()}", result.failures.isEmpty())
        assertTrue("tunnel counters did not grow during the soak", result.trafficGrew)
        assertFalse("fatal runtime evidence was recorded during the soak", result.fatalRuntimeEvidence)
        assertTrue("successful tunnel traffic evidence was missing", result.successfulTunnelEvidence)
        assertTrue(
            "IP refresh failed in ${result.scheduledIpFailures} scheduled probes",
            result.scheduledIpFailures <= SCHEDULED_IP_FAILURE_BUDGET,
        )
        assertTrue(
            "IP refresh failed in ${result.maxConsecutiveIpFailures} consecutive probes",
            result.maxConsecutiveIpFailures < IP_FAILURE_STREAK_LIMIT,
        )
        assertTrue("final VPN-bound IP proof was missing", result.finalIpProofOk)
        assertResourceTrends(
            samples = result.resources,
            minimumSamples = maxOf(PLATEAU_WINDOW_SAMPLES, result.minimumProbes),
        )
    }

    private suspend fun assertStoppedCleanly(app: FoxholeApplication) {
        disconnectAndWaitForIdle(app)
        FoxholeConnectionServiceContract.stopAllServices(app)
        val stopped =
            waitUntil(timeoutMs = CLEANUP_TIMEOUT_MS) {
                app.container.connectionController.snapshot.value.state == ConnectionState.IDLE &&
                    !hasFoxholeRuntimeServices(app) &&
                    !hasActiveFoxholeVpnNetwork(app)
            }
        assertTrue("protocol soak runtime did not stop completely", stopped)
        assertEquals(ConnectionState.IDLE, app.container.connectionController.snapshot.value.state)
        assertFalse("protocol soak left a FoxHole runtime service", hasFoxholeRuntimeServices(app))
        assertFalse("protocol soak left an active FoxHole VPN network", hasActiveFoxholeVpnNetwork(app))
    }

    private suspend fun hasSuccessfulIpProof(app: FoxholeApplication): Boolean =
        runVpnBoundIpRefresh(app).strictNumericIpFamilyOrNull() != null

    private fun String.strictNumericIpFamilyOrNull(): String? {
        if (!startsWith(IP_REFRESH_OK_PREFIX)) return null
        val candidate = removePrefix(IP_REFRESH_OK_PREFIX)
        if (candidate.isBlank() || candidate != candidate.trim() || !candidate.isNumericIpText()) return null
        val parsed = runCatching { InetAddress.getByName(candidate) }.getOrNull() ?: return null
        return if (parsed.address.size == IPV4_ADDRESS_BYTES) "ipv4" else "ipv6"
    }

    private fun String.isNumericIpText(): Boolean =
        if (contains(':')) {
            all { character ->
                character == ':' ||
                    character == '.' ||
                    character in '0'..'9' ||
                    character.lowercaseChar() in 'a'..'f'
            }
        } else {
            val octets = split('.')
            octets.size == IPV4_OCTETS &&
                octets.all { octet ->
                    octet.isNotEmpty() &&
                        octet.length <= IPV4_OCTET_MAX_LENGTH &&
                        octet.all { character -> character in '0'..'9' } &&
                        octet.toInt() in IPV4_OCTET_RANGE
                }
        }

    private fun captureResources(): ResourceSample {
        val runtime = Runtime.getRuntime()
        return ResourceSample(
            rssKb = readProcStatusKb("VmRSS"),
            pssKb = readCurrentPssKb(),
            nativeHeapKb = runCatching { Debug.getNativeHeapAllocatedSize() / BYTES_PER_KB }.getOrNull(),
            javaHeapKb = ((runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_KB).coerceAtLeast(0L),
            threadCount = runCatching { Thread.getAllStackTraces().size }.getOrDefault(-1),
            fdCount = runCatching { File("/proc/self/fd").list()?.size ?: -1 }.getOrDefault(-1),
        )
    }

    private fun assertResourceTrends(
        samples: List<ResourceSample>,
        minimumSamples: Int,
    ) {
        assertTrue("protocol soak produced too few resource samples", samples.size >= minimumSamples)
        assertNoConsecutiveHardLimit(
            "RSS",
            samples.map(ResourceSample::rssKb),
            RSS_HARD_LIMIT_KB,
            minimumSamples,
        )
        assertNoConsecutiveHardLimit(
            "PSS",
            samples.map(ResourceSample::pssKb),
            PSS_HARD_LIMIT_KB,
            minimumSamples,
        )
        assertBoundedPlateauGrowth(
            "RSS",
            samples.map(ResourceSample::rssKb),
            RSS_DELTA_LIMIT_KB,
            minimumSamples,
        )
        assertBoundedPlateauGrowth(
            "PSS",
            samples.map(ResourceSample::pssKb),
            PSS_DELTA_LIMIT_KB,
            minimumSamples,
        )
        assertBoundedPlateauGrowth(
            "native heap",
            samples.map(ResourceSample::nativeHeapKb),
            NATIVE_HEAP_DELTA_LIMIT_KB,
            minimumSamples,
        )
        assertBoundedPlateauGrowth(
            "Java heap",
            samples.map { it.javaHeapKb },
            JAVA_HEAP_DELTA_LIMIT_KB,
            minimumSamples,
        )
        assertBoundedPlateauGrowth(
            "threads",
            samples.map { it.threadCount.takeIf { count -> count >= 0 }?.toLong() },
            THREAD_DELTA_LIMIT,
            minimumSamples,
            relativeAllowance = false,
        )
        assertBoundedPlateauGrowth(
            "file descriptors",
            samples.map { it.fdCount.takeIf { count -> count >= 0 }?.toLong() },
            FD_DELTA_LIMIT,
            minimumSamples,
            relativeAllowance = false,
        )
    }

    private fun assertNoConsecutiveHardLimit(
        label: String,
        samples: List<Long?>,
        hardLimit: Long,
        minimumSamples: Int,
    ) {
        var consecutiveBreaches = 0
        var peak = 0L
        var validSamples = 0
        samples.forEach { sample ->
            if (sample == null) {
                consecutiveBreaches = 0
            } else {
                validSamples += 1
                peak = maxOf(peak, sample)
                consecutiveBreaches = if (sample >= hardLimit) consecutiveBreaches + 1 else 0
                assertTrue(
                    "$label exceeded hard limit in $consecutiveBreaches consecutive samples: " +
                        "peak=$peak limit=$hardLimit",
                    consecutiveBreaches < HARD_LIMIT_CONSECUTIVE_SAMPLES,
                )
            }
        }
        assertTrue("$label sampling was incomplete: $validSamples/$minimumSamples", validSamples >= minimumSamples)
    }

    private fun assertBoundedPlateauGrowth(
        label: String,
        samples: List<Long?>,
        fixedDeltaLimit: Long,
        minimumSamples: Int,
        relativeAllowance: Boolean = true,
    ) {
        val values = samples.filterNotNull()
        assertTrue("$label sampling was incomplete: ${values.size}/$minimumSamples", values.size >= minimumSamples)
        val baseline = median(values.take(PLATEAU_WINDOW_SAMPLES))
        val end = median(values.takeLast(PLATEAU_WINDOW_SAMPLES))
        val allowedDelta = if (relativeAllowance) maxOf(fixedDeltaLimit, baseline / 2L) else fixedDeltaLimit
        assertTrue(
            "$label plateau growth exceeded the soak budget: " +
                "baseline=$baseline end=$end allowedDelta=$allowedDelta",
            end <= baseline + allowedDelta,
        )
    }

    private fun median(values: List<Long>): Long {
        val ordered = values.sorted()
        val middle = ordered.size / 2
        return if (ordered.size % 2 == 1) {
            ordered[middle]
        } else {
            ordered[middle - 1] + (ordered[middle] - ordered[middle - 1]) / 2L
        }
    }

    private fun minimumProbeCount(
        durationMs: Long,
        probeIntervalMs: Long,
    ): Int {
        val count = durationMs / probeIntervalMs + if (durationMs % probeIntervalMs == 0L) 0L else 1L
        require(count in 1..Int.MAX_VALUE.toLong()) { "protocol soak probe count is out of range" }
        return count.toInt()
    }

    private fun requiredProtocol(): ProtocolHint {
        val raw = argument(ARG_PROTOCOL).orEmpty().trim()
        assertTrue("pass exactly one protocol with -e $ARG_PROTOCOL <PROTOCOL>", raw.isNotEmpty() && ',' !in raw)
        val protocol = storedProtocolHintOrNull(raw.uppercase())
        assertNotNull("unsupported protocol argument", protocol)
        return requireNotNull(protocol)
    }

    private fun requiredRawSubscription(): String {
        val rawFile = argument(ARG_RAW_SUBSCRIPTION_FILE).orEmpty().trim()
        assertTrue("pass the staged raw subscription with -e $ARG_RAW_SUBSCRIPTION_FILE <path>", rawFile.isNotEmpty())
        val subscription = readDeviceTextFile(rawFile)
        assertTrue("staged raw subscription is missing or empty", !subscription.isNullOrBlank())
        return requireNotNull(subscription)
    }

    private fun positiveDurationArgument(name: String, defaultValue: Long): Long {
        val value = longArgument(name, defaultValue)
        assertTrue("$name must be positive", value > 0L)
        if (name == ARG_DURATION_MS) {
            assertTrue("$ARG_DURATION_MS must cover at least 15 minutes", value >= MIN_DURATION_MS)
        }
        return value
    }

    private fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

    private data class PreparedTarget(
        val profileId: Long,
        val optionId: String?,
    )

    private class IpProofTracker {
        var scheduledFailures: Int = 0
            private set
        var consecutiveFailures: Int = 0
            private set
        var maxConsecutiveFailures: Int = 0
            private set

        fun recordScheduled(success: Boolean) {
            if (!success) scheduledFailures += 1
            record(success)
        }

        fun recordFinal(success: Boolean) {
            record(success)
        }

        private fun record(success: Boolean) {
            if (success) {
                consecutiveFailures = 0
                return
            }
            consecutiveFailures += 1
            maxConsecutiveFailures = maxOf(maxConsecutiveFailures, consecutiveFailures)
        }
    }

    private data class ResourceSample(
        val rssKb: Long?,
        val pssKb: Long?,
        val nativeHeapKb: Long?,
        val javaHeapKb: Long,
        val threadCount: Int,
        val fdCount: Int,
    )

    private data class SoakResult(
        val probes: Int,
        val minimumProbes: Int,
        val deadlineCovered: Boolean,
        val failures: List<String>,
        val finalState: ConnectionState,
        val trafficGrew: Boolean,
        val fatalRuntimeEvidence: Boolean,
        val successfulTunnelEvidence: Boolean,
        val scheduledIpFailures: Int,
        val maxConsecutiveIpFailures: Int,
        val finalIpProofOk: Boolean,
        val resources: List<ResourceSample>,
    )

    private companion object {
        const val ARG_ENABLED = "foxhole.liveProtocolSoak"
        const val ARG_PROTOCOL = "foxhole.protocolSoakProtocol"
        const val ARG_RAW_SUBSCRIPTION_FILE = "foxhole.smartSubscriptionRawFile"
        const val ARG_DURATION_MS = "foxhole.protocolSoakDurationMs"
        const val ARG_PROBE_INTERVAL_MS = "foxhole.protocolSoakProbeIntervalMs"
        const val ARG_ALLOW_INSECURE_TLS = "foxhole.allowInsecureTlsForLiveSubscription"
        const val DEFAULT_DURATION_MS = 15L * 60L * 1_000L
        const val MIN_DURATION_MS = DEFAULT_DURATION_MS
        const val DEFAULT_PROBE_INTERVAL_MS = 60_000L
        const val LATENCY_TIMEOUT_MS = 8_000L
        const val CLEANUP_TIMEOUT_MS = 30_000L
        const val UI_SETTLE_MS = 2_000L
        const val RUNTIME_PHASE_CONNECTED = "connected"
        const val MILLIS_PER_SECOND = 1_000L
        const val IP_REFRESH_OK_PREFIX = "ok:"
        const val IPV4_ADDRESS_BYTES = 4
        const val IPV4_OCTETS = 4
        const val IPV4_OCTET_MAX_LENGTH = 3
        val IPV4_OCTET_RANGE = 0..255
        const val PLATEAU_WINDOW_SAMPLES = 3
        const val SCHEDULED_IP_FAILURE_BUDGET = 1
        const val IP_FAILURE_STREAK_LIMIT = 2
        const val HARD_LIMIT_CONSECUTIVE_SAMPLES = 2
        const val RSS_HARD_LIMIT_KB = 650L * 1_024L
        const val PSS_HARD_LIMIT_KB = 525L * 1_024L
        const val RSS_DELTA_LIMIT_KB = 250L * 1_024L
        const val PSS_DELTA_LIMIT_KB = 150L * 1_024L
        const val NATIVE_HEAP_DELTA_LIMIT_KB = 150L * 1_024L
        const val JAVA_HEAP_DELTA_LIMIT_KB = 80L * 1_024L
        const val THREAD_DELTA_LIMIT = 32L
        const val FD_DELTA_LIMIT = 32L
    }
}
