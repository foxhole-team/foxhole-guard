package com.foxhole.guard

import android.content.pm.ApplicationInfo
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.NetworkRulesSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SecureDnsMode
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.guard.core.data.ProfileEntity
import com.foxhole.guard.core.settings.quarantinePackage
import com.foxhole.guard.core.settings.resolveQuarantinedApp
import com.foxhole.guard.core.settings.updateAnomalyEnabled
import com.foxhole.guard.core.settings.updateAppTrafficStatsEnabled
import com.foxhole.guard.core.settings.updateDnsSettings
import com.foxhole.guard.core.settings.updateFirewallEnabled
import com.foxhole.guard.core.settings.updateNetworkActivityLogging
import com.foxhole.guard.core.settings.updateNewAppQuarantineEnabled
import com.foxhole.guard.core.settings.updateStatisticsEnabled
import com.foxhole.guard.core.settings.updateTrafficMapEnabled
import com.foxhole.guard.runtime.DnsFilterUpdateStatus
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.FoxholeVpnService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.Executor

@SdkSuppress(minSdkVersion = 29)
@RequiresApi(Build.VERSION_CODES.Q)
@RunWith(AndroidJUnit4::class)
internal class LiveSentinelDnsProtectionAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun pendingSentinelQuarantineIsAppliedAndReleasedByTheLiveFirewall() =
        runBlocking {
            assumeEnabled(ARG_SENTINEL_QUARANTINE)
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val quarantineTarget = requireDisposableTestPackage(app)
            assertTrue("VPN permission is required for quarantine enforcement", ensureVpnPermission(app))
            assertRuntimeIdle(app)

            val checkpoint = app.container.settingsRepository.current()
            val profileCheckpoint = app.container.profileDatabase.profileDao().getAllProfiles()
            var primaryFailure: Throwable? = null
            try {
                installIsolatedSettings(app, checkpoint)
                app.container.settingsRepository.updateNewAppQuarantineEnabled(true)
                startLocalFirewall(app)

                val beforeConfig = assembleLocalFirewallConfig(app)
                assertFalse("test target was blocked before quarantine", configBlocksPackage(beforeConfig, quarantineTarget))
                val beforeSignature = app.container.connectionController.appliedRuntimeSignature.value
                assertNotNull("initial LocalGuard fingerprint was not acknowledged", beforeSignature)
                val beforeRevision = app.container.settingsRepository.current().expert.quarantinePolicyRevision
                val beforeNetworkHandle =
                    requireNotNull(app.container.connectionController.currentVpnNetwork()).networkHandle

                app.container.settingsRepository.update { current ->
                    current.copy(
                        expert = current.expert.quarantinePackage(quarantineTarget),
                    )
                }
                val pending = app.container.settingsRepository.current()
                assertTrue("quarantine decision was not persisted", quarantineTarget in pending.expert.pendingQuarantinePackages)
                assertEquals(AppTunnelLane.BLOCK, pending.expert.appAssignments[quarantineTarget])
                assertTrue("quarantine revision did not advance", pending.expert.quarantinePolicyRevision > beforeRevision)

                assertQuarantineRevisionApplied(app, pending.expert.quarantinePolicyRevision, beforeNetworkHandle)
                val blockedSignature = app.container.connectionController.appliedRuntimeSignature.value
                assertNotEquals("quarantine did not replace the applied runtime policy", beforeSignature, blockedSignature)
                assertTrue(
                    "applied LocalGuard config did not contain the quarantine block",
                    configBlocksPackage(assembleLocalFirewallConfig(app), quarantineTarget),
                )
                assertEquals(
                    "quarantine enforcement replaced the LocalGuard VPN network",
                    beforeNetworkHandle,
                    requireNotNull(app.container.connectionController.currentVpnNetwork()).networkHandle,
                )

                app.container.settingsRepository.resolveQuarantinedApp(quarantineTarget, keepBlocked = false)
                val released = app.container.settingsRepository.current()
                assertFalse("resolved quarantine remained pending", quarantineTarget in released.expert.pendingQuarantinePackages)
                assertFalse(
                    "resolved quarantine remained in the BLOCK lane",
                    released.expert.appAssignments[quarantineTarget] == AppTunnelLane.BLOCK,
                )
                assertTrue(
                    "released application identity was not pinned into the quarantine baseline",
                    released.expert.quarantineKnownApplications.any { identity -> identity.packageName == quarantineTarget },
                )

                assertQuarantineRevisionApplied(app, released.expert.quarantinePolicyRevision, beforeNetworkHandle)
                assertFalse(
                    "released quarantine remained in the applied LocalGuard config",
                    configBlocksPackage(assembleLocalFirewallConfig(app), quarantineTarget),
                )
                assertEquals(
                    "quarantine release replaced the LocalGuard VPN network",
                    beforeNetworkHandle,
                    requireNotNull(app.container.connectionController.currentVpnNetwork()).networkHandle,
                )
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                val cleanupFailures =
                    withContext(NonCancellable) {
                        restoreDisposableState(app, checkpoint, profileCheckpoint)
                    }
                propagateCleanupFailures(primaryFailure, cleanupFailures)
            }
        }

    @Test
    fun verifiedDnsFilterBlocksAndAllowsQueriesThroughVpnAndLocalGuard() =
        runBlocking {
            assumeEnabled(ARG_DNS_FILTER)
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            requireDisposableTestPackage(app)
            assertTrue("VPN permission is required for DNS enforcement", ensureVpnPermission(app))
            assertRuntimeIdle(app)

            val checkpoint = app.container.settingsRepository.current()
            val profileCheckpoint = app.container.profileDatabase.profileDao().getAllProfiles()
            var primaryFailure: Throwable? = null
            try {
                assertTrue(
                    "live DNS filter test requires a clean disposable profile database",
                    profileCheckpoint.isEmpty(),
                )
                installIsolatedSettings(app, checkpoint)
                val disabledDns = dnsSettingsForLiveTest(app.container.settingsRepository.current().dns, enabled = false)
                app.container.settingsRepository.updateDnsSettings(disabledDns)
                ensureVerifiedDnsRuleSet(app, dnsSettingsForLiveTest(disabledDns, enabled = true))
                val upstream = app.container.connectionController.currentUpstreamNetwork()
                assertNotNull("non-VPN upstream was unavailable for the DNS control", upstream)
                assertTrue("allowed DNS control did not resolve before filtering", queryResolves(upstream, ALLOWED_CONTROL))
                val directlyResolvableProbes = directlyResolvableBlockedProbes(upstream)
                assertTrue("no DNS filter probe resolved before filtering", directlyResolvableProbes.isNotEmpty())

                val profileTarget = importDnsTestTarget(app, profileCheckpoint)
                startProfileTunnel(app, profileTarget.profile.id, profileTarget.target.optionId)
                val profileNetworkHandle = requireNotNull(app.container.connectionController.currentVpnNetwork()).networkHandle
                val profileBlockedProbe = firstResolvableProbeOnActiveVpn(app, directlyResolvableProbes)
                assertNotNull("no DNS filter probe resolved through the profile tunnel", profileBlockedProbe)
                val selectedProfileBlockedProbe = requireNotNull(profileBlockedProbe)
                assertDnsProbeAllowed(app, selectedProfileBlockedProbe)
                applyDnsFilteringAndAwait(
                    app,
                    enabled = true,
                    profileId = profileTarget.profile.id,
                    networkHandle = profileNetworkHandle,
                )
                assertDnsFilterEnforced(app, selectedProfileBlockedProbe)
                applyDnsFilteringAndAwait(
                    app,
                    enabled = false,
                    profileId = profileTarget.profile.id,
                    networkHandle = profileNetworkHandle,
                )
                assertDnsProbeAllowed(app, selectedProfileBlockedProbe)

                stopRuntime(app)
                app.container.settingsRepository.update { current ->
                    current.copy(
                        webApps = current.webApps.copy(enabled = true, pushServiceEnabled = false),
                    )
                }
                app.container.settingsRepository.updateFirewallEnabled(true)
                startLocalFirewall(app)
                val localGuardNetworkHandle =
                    requireNotNull(app.container.connectionController.currentVpnNetwork()).networkHandle
                val localGuardBlockedProbe = firstResolvableProbeOnActiveVpn(app, directlyResolvableProbes)
                assertNotNull("no DNS filter probe resolved through LocalGuard", localGuardBlockedProbe)
                val selectedLocalGuardBlockedProbe = requireNotNull(localGuardBlockedProbe)
                assertDnsProbeAllowed(app, selectedLocalGuardBlockedProbe)
                applyDnsFilteringAndAwait(
                    app,
                    enabled = true,
                    profileId = null,
                    networkHandle = localGuardNetworkHandle,
                )
                assertDnsFilterEnforced(app, selectedLocalGuardBlockedProbe)
                applyDnsFilteringAndAwait(
                    app,
                    enabled = false,
                    profileId = null,
                    networkHandle = localGuardNetworkHandle,
                )
                assertDnsProbeAllowed(app, selectedLocalGuardBlockedProbe)
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                val cleanupFailures =
                    withContext(NonCancellable) {
                        restoreDisposableState(app, checkpoint, profileCheckpoint)
                    }
                propagateCleanupFailures(primaryFailure, cleanupFailures)
            }
        }

    private fun assumeEnabled(argument: String) {
        assumeTrue(
            "live hardware protection test is disabled; pass its explicit instrumentation flag",
            InstrumentationRegistry.getArguments().getString(argument) == "1",
        )
    }

    private fun requireDisposableTestPackage(app: FoxholeApplication): String {
        assertTrue(
            "hardware protection test may run only in a debuggable target",
            app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        assertTrue(
            "hardware protection test may run only against the debug application",
            app.packageName.endsWith(DEBUG_APPLICATION_SUFFIX),
        )
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        assertTrue(
            "quarantine target must be the isolated instrumentation package",
            testPackage == "${app.packageName}.test",
        )
        assertTrue(
            "isolated instrumentation package is not installed",
            runCatching { app.packageManager.getPackageInfo(testPackage, 0) }.isSuccess,
        )
        return testPackage
    }

    private suspend fun installIsolatedSettings(
        app: FoxholeApplication,
        checkpoint: com.foxhole.core.model.Settings,
    ) {
        val isolated =
            checkpoint.copy(
                connection =
                    checkpoint.connection.copy(
                        safeModeEnabled = false,
                        autoReconnect = false,
                        autoStartOnBoot = false,
                        autoRefreshSubscriptions = false,
                        componentAutoUpdateEnabled = false,
                        tlsFingerprintAutoUpdate = false,
                    ),
                traffic = checkpoint.traffic.copy(mode = TrafficMode.TUNNEL),
                networkRules = NetworkRulesSettings(),
                privacyRoute = checkpoint.privacyRoute.copy(permitted = false, mode = PrivacyRouteMode.OFF),
                i2p = checkpoint.i2p.copy(enabled = false, engaged = false),
                webApps = checkpoint.webApps.copy(enabled = false, pushServiceEnabled = false),
                dns =
                    checkpoint.dns.copy(
                        useVpnProviderDns = true,
                        dnsThroughVpn = true,
                        blockOutsideTunnel = true,
                        filteringEnabled = false,
                        interceptDnsRequests = false,
                        replaceSystemDns = false,
                        server = TEST_DNS_SERVER,
                        secureMode = SecureDnsMode.PLAIN,
                        autoUpdateFilters = false,
                        appBypassPackages = emptyList(),
                        domainBypassRules = emptyList(),
                    ),
                expert =
                    checkpoint.expert.copy(
                        firewallEnabled = false,
                        networkActivityLogging = false,
                        rawLiveDiagnostics = false,
                        newAppQuarantineEnabled = false,
                        quarantineKnownApplications = emptyList(),
                        pendingQuarantinePackages = emptyList(),
                        pendingQuarantineAppDetails = emptyList(),
                        perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
                        appAssignments = emptyMap(),
                        blockedPackagesEnabled = false,
                        blockAppsAlways = false,
                        killSwitchEnabled = false,
                    ),
            )
        app.container.settingsRepository.restoreExactCheckpoint(isolated)
        app.container.settingsRepository.updateStatisticsEnabled(false)
        app.container.settingsRepository.updateAppTrafficStatsEnabled(false)
        app.container.settingsRepository.updateAnomalyEnabled(false)
        app.container.settingsRepository.updateTrafficMapEnabled(false)
        app.container.settingsRepository.updateNetworkActivityLogging(false)
    }

    private suspend fun startLocalFirewall(app: FoxholeApplication) {
        app.container.connectionController.syncLocalGuard()
        assertTrue(
            "LocalGuard firewall did not reach an acknowledged CONNECTED runtime",
            waitUntil(LOCAL_GUARD_START_TIMEOUT_MS) {
                val controller = app.container.connectionController
                controller.snapshot.value.state == ConnectionState.CONNECTED &&
                    controller.snapshot.value.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
                    controller.hasActiveVpnNetwork() &&
                    controller.appliedRuntimeSignature.value ==
                    controller.currentLocalGuardRuntimeFingerprint(LocalGuardMode.FIREWALL)
            },
        )
    }

    private suspend fun assertQuarantineRevisionApplied(
        app: FoxholeApplication,
        revision: Long,
        networkHandle: Long,
    ) {
        val controller = app.container.connectionController
        assertEquals(
            "quarantine revision was not persisted before runtime enforcement",
            revision,
            app.container.settingsRepository.current().expert.quarantinePolicyRevision,
        )
        val expectedSignature = controller.currentLocalGuardRuntimeFingerprint(LocalGuardMode.FIREWALL)
        assertTrue(
            "production quarantine worker did not apply the expected native runtime policy",
            waitUntil(QUARANTINE_APPLY_TIMEOUT_MS) {
                val snapshot = controller.snapshot.value
                snapshot.state == ConnectionState.CONNECTED &&
                    snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
                    controller.hasActiveVpnNetwork() &&
                    controller.currentVpnNetwork()?.networkHandle == networkHandle &&
                    app.container.settingsRepository.settings.value.expert.quarantinePolicyRevision == revision &&
                    controller.appliedRuntimeSignature.value == expectedSignature
            },
        )
        val finalSnapshot = controller.snapshot.value
        assertEquals(
            "production quarantine worker changed the LocalGuard connection state",
            ConnectionState.CONNECTED,
            finalSnapshot.state,
        )
        assertEquals(
            "production quarantine worker replaced the LocalGuard owner",
            FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
            finalSnapshot.profileId,
        )
        assertTrue(
            "production quarantine worker removed the LocalGuard VPN network",
            controller.hasActiveVpnNetwork(),
        )
        assertEquals(
            "production quarantine worker replaced the LocalGuard VPN network",
            networkHandle,
            controller.currentVpnNetwork()?.networkHandle,
        )
        assertEquals(
            "persisted quarantine revision changed after runtime enforcement",
            revision,
            app.container.settingsRepository.current().expert.quarantinePolicyRevision,
        )
        assertEquals(
            "native runtime did not acknowledge the expected quarantine policy",
            expectedSignature,
            controller.appliedRuntimeSignature.value,
        )
    }

    private suspend fun startProfileTunnel(
        app: FoxholeApplication,
        profileId: Long,
        optionId: String?,
    ) {
        app.container.connectionController.connect(profileId, protocolOptionId = optionId)
        val terminalState =
            withTimeoutOrNull(PROFILE_START_TIMEOUT_MS) {
                waitForActiveConnectionAttempt(app)
                waitForTerminalState(app)
            }
        assertEquals("DNS-filter profile did not connect", ConnectionState.CONNECTED, terminalState)
        assertTrue(
            "DNS-filter profile did not expose its VPN network",
            app.container.connectionController.hasActiveVpnNetwork(),
        )
    }

    private suspend fun ensureVerifiedDnsRuleSet(
        app: FoxholeApplication,
        enabledDns: DnsSettings,
    ) {
        if (app.container.dnsFilterAssetInstaller.prepareVerifiedOrNull() != null) {
            return
        }
        val refresh =
            app.container.dnsFilterUpdateRepository.refreshNow(
                requireAutoEnabled = false,
                dnsSettingsOverride = enabledDns,
            )
        assertTrue(
            "signed DNS rule-set refresh failed",
            refresh.status == DnsFilterUpdateStatus.UPDATED ||
                refresh.status == DnsFilterUpdateStatus.UP_TO_DATE,
        )
        assertNotNull(
            "signed DNS rule set was not available after verified refresh",
            app.container.dnsFilterAssetInstaller.prepareVerifiedOrNull(),
        )
    }

    private suspend fun importDnsTestTarget(
        app: FoxholeApplication,
        profileCheckpoint: List<ProfileEntity>,
    ): DnsProfileTarget {
        assertTrue(
            "private live subscription import requires an empty disposable profile database",
            profileCheckpoint.isEmpty(),
        )
        val subscription = smartSubscriptionInput()
        assertNotNull("private live subscription input was unavailable", subscription)
        runCatching {
            app.container.profileRepository.importProfile(
                rawInput = requireNotNull(subscription),
                preferredName = "Live DNS enforcement",
                allowInsecureTlsForProfile =
                    InstrumentationRegistry.getArguments()
                        .getString("foxhole.allowInsecureTlsForLiveSubscription") == "1",
            )
        }.getOrElse {
            throw AssertionError("private live subscription could not be imported")
        }

        val profilesAfterImport = app.container.profileRepository.profiles.first()
        val checkpointIds = profileCheckpoint.mapTo(mutableSetOf()) { profile -> profile.id }
        val importedProfiles = profilesAfterImport.filterNot { profile -> profile.id in checkpointIds }
        assertTrue("private live subscription created no disposable profiles", importedProfiles.isNotEmpty())
        val candidates =
            importedProfiles.flatMap { profile ->
                enabledDnsTargets(profile).map { target -> DnsProfileTarget(profile, target) }
            }
        val selected =
            candidates.firstOrNull { candidate -> candidate.target.protocolHint == ProtocolHint.VLESS }
                ?: candidates.firstOrNull()
        assertNotNull("private live subscription exposed no DNS-filter-compatible protocol", selected)
        return requireNotNull(selected)
    }

    private fun enabledDnsTargets(profile: Profile): List<RuntimeProbeTarget> {
        val enabledOptionIds =
            profile.protocolOptions
                .filter { option -> option.enabled }
                .mapTo(mutableSetOf()) { option -> option.id }
        return profile.runtimeProbeTargets().filter { target ->
            target.protocolHint != ProtocolHint.WIREGUARD &&
                (target.optionId == null || target.optionId in enabledOptionIds)
        }
    }

    private fun dnsSettingsForLiveTest(
        dns: DnsSettings,
        enabled: Boolean,
    ): DnsSettings =
        dns.copy(
            filteringEnabled = enabled,
            blockAds = true,
            blockTrackers = true,
            blockAppTelemetry = true,
            blockMaliciousDomains = true,
            interceptDnsRequests = true,
            replaceSystemDns = false,
            autoUpdateFilters = false,
            appBypassPackages = emptyList(),
            domainBypassRules = emptyList(),
        )

    private suspend fun applyDnsFilteringAndAwait(
        app: FoxholeApplication,
        enabled: Boolean,
        profileId: Long?,
        networkHandle: Long,
    ) {
        val controller = app.container.connectionController
        val previousSignature = controller.appliedRuntimeSignature.value
        val settings = app.container.settingsRepository
        settings.updateDnsSettings(dnsSettingsForLiveTest(settings.current().dns, enabled))
        val expectedSignature =
            if (profileId == null) {
                controller.currentLocalGuardRuntimeFingerprint(LocalGuardMode.FIREWALL)
            } else {
                controller.currentRuntimeFingerprint()
            }
        val dispatched =
            if (profileId == null) {
                controller.syncLocalGuard()
                true
            } else {
                controller.reload(profileId)
            }
        assertTrue("DNS filter runtime reload was not dispatched", dispatched)
        val expectedOwner = profileId ?: FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
        assertTrue(
            "DNS filter runtime reload did not acknowledge the expected policy",
            waitUntil(RUNTIME_RELOAD_TIMEOUT_MS) {
                val snapshot = controller.snapshot.value
                snapshot.state == ConnectionState.CONNECTED &&
                    snapshot.profileId == expectedOwner &&
                    controller.currentVpnNetwork()?.networkHandle == networkHandle &&
                    controller.appliedRuntimeSignature.value == expectedSignature
            },
        )
        assertNotEquals(
            "DNS filter toggle did not change the applied runtime fingerprint",
            previousSignature,
            expectedSignature,
        )
    }

    private suspend fun assertDnsProbeAllowed(
        app: FoxholeApplication,
        blockedProbe: String,
    ) {
        val network = app.container.connectionController.currentVpnNetwork()
        assertNotNull("active FoxHole VPN network was unavailable to the DNS control", network)
        assertTrue("allowed DNS control did not resolve", queryResolves(network, ALLOWED_CONTROL))
        assertTrue("DNS block probe did not resolve while filtering was disabled", queryResolves(network, blockedProbe))
    }

    private suspend fun assertDnsFilterEnforced(
        app: FoxholeApplication,
        blockedProbe: String,
    ) {
        val network = app.container.connectionController.currentVpnNetwork()
        assertNotNull("active FoxHole VPN network was unavailable to the DNS probe", network)
        val before = runtimeDnsBlocked(app)
        assertTrue("native DNS counters were unavailable before the probe", before >= 0L)
        assertTrue("allowed DNS control was rejected by the filter", queryResolves(network, ALLOWED_CONTROL))
        assertFalse("blocked DNS probe was resolved by the filter", queryResolves(network, blockedProbe))
        assertTrue(
            "native DNS blocked counter did not record the refusal",
            waitUntil(DNS_COUNTER_TIMEOUT_MS) { runtimeDnsBlocked(app) > before },
        )
    }

    private fun assembleLocalFirewallConfig(app: FoxholeApplication): String =
        app.container.runtimeConfigAssembler.assembleLocalGuard(
            settings = app.container.settingsRepository.settings.value,
            mode = LocalGuardMode.FIREWALL,
        )

    private fun configBlocksPackage(
        encoded: String,
        packageName: String,
    ): Boolean =
        json.parseToJsonElement(encoded).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray
            .map { element -> element.jsonObject }
            .any { rule ->
                rule["outbound"]?.jsonPrimitive?.content == "block" &&
                    rule["package_name"]?.jsonArray?.any { value -> value.jsonPrimitive.content == packageName } == true
            }

    private fun runtimeDnsBlocked(app: FoxholeApplication): Long =
        app.container.runtimeInstanceStore.current()?.runtimeStatsJson()?.let { encoded ->
            runCatching { JSONObject(encoded).optLong("dns_blocked", -1L) }.getOrDefault(-1L)
        } ?: -1L

    private suspend fun directlyResolvableBlockedProbes(network: Network?): List<String> =
        BLOCKED_PROBE_CANDIDATES.filter { candidate -> queryResolves(network, candidate) }

    private suspend fun firstResolvableProbeOnActiveVpn(
        app: FoxholeApplication,
        candidates: List<String>,
    ): String? {
        val network = app.container.connectionController.currentVpnNetwork() ?: return null
        return candidates.firstOrNull { candidate -> queryResolves(network, candidate) }
    }

    private suspend fun queryResolves(
        network: Network?,
        domain: String,
    ): Boolean =
        withTimeoutOrNull(DNS_QUERY_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }
                DnsResolver.getInstance().query(
                    network,
                    domain,
                    DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE,
                    DIRECT_EXECUTOR,
                    cancellation,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(
                            answer: List<InetAddress>,
                            rcode: Int,
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(rcode == 0 && answer.isNotEmpty()))
                            }
                        }

                        override fun onError(@Suppress("UNUSED_PARAMETER") error: DnsResolver.DnsException) {
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(false))
                            }
                        }
                    },
                )
            }
        } ?: false

    private suspend fun assertRuntimeIdle(app: FoxholeApplication) {
        assertEquals(
            "hardware protection test requires an idle debug runtime",
            ConnectionState.IDLE,
            app.container.connectionController.snapshot.value.state,
        )
        assertFalse("hardware protection test found a debug runtime service", hasFoxholeRuntimeServices(app))
        assertFalse("hardware protection test found an active debug VPN", hasActiveFoxholeVpnNetwork(app))
    }

    private suspend fun stopRuntime(app: FoxholeApplication) {
        val failures = mutableListOf<Throwable>()
        failures.captureCleanupFailure {
            app.container.connectionController.disconnect(suppressLocalGuard = true)
        }
        val stoppedGracefully =
            waitUntil(RUNTIME_STOP_TIMEOUT_MS) {
                app.container.connectionController.snapshot.value.state == ConnectionState.IDLE &&
                    !hasFoxholeRuntimeServices(app) &&
                    !hasActiveFoxholeVpnNetwork(app)
            }
        if (!stoppedGracefully) {
            failures.captureCleanupFailure {
                FoxholeConnectionServiceContract.stopAllServices(app)
            }
        }
        val stopped =
            stoppedGracefully ||
                waitUntil(RUNTIME_STOP_TIMEOUT_MS) {
                    app.container.connectionController.snapshot.value.state == ConnectionState.IDLE &&
                        !hasFoxholeRuntimeServices(app) &&
                        !hasActiveFoxholeVpnNetwork(app)
                }
        if (!stopped) {
            failures += AssertionError("debug runtime did not stop cleanly")
        }
        val primary = failures.firstOrNull() ?: return
        failures.drop(1).forEach(primary::addSuppressed)
        throw primary
    }

    private suspend fun restoreDisposableState(
        app: FoxholeApplication,
        checkpoint: com.foxhole.core.model.Settings,
        profileCheckpoint: List<ProfileEntity>,
    ): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        failures.captureCleanupFailure {
            stopRuntime(app)
        }
        failures.captureCleanupFailure {
            restoreProfiles(app, profileCheckpoint)
        }
        failures.captureCleanupFailure {
            app.container.settingsRepository.restoreExactCheckpoint(checkpoint)
        }
        failures.captureCleanupFailure {
            app.applyGuardReconcileSchedule(enabled = checkpoint.localGuardModeOrNull() != null)
        }
        failures.captureCleanupFailure {
            assertEquals(
                "hardware protection test left the runtime controller active",
                ConnectionState.IDLE,
                app.container.connectionController.snapshot.value.state,
            )
        }
        failures.captureCleanupFailure {
            assertFalse("hardware protection test left a debug runtime service active", hasFoxholeRuntimeServices(app))
        }
        failures.captureCleanupFailure {
            assertFalse("hardware protection test left a debug VPN active", hasActiveFoxholeVpnNetwork(app))
        }
        failures.captureCleanupFailure {
            assertTrue(
                "hardware protection test changed the profile database",
                profileCheckpoint == app.container.profileDatabase.profileDao().getAllProfiles(),
            )
        }
        failures.captureCleanupFailure {
            assertTrue(
                "settings checkpoint was not restored",
                checkpoint == app.container.settingsRepository.current(),
            )
        }
        return failures
    }

    private suspend fun restoreProfiles(
        app: FoxholeApplication,
        profileCheckpoint: List<ProfileEntity>,
    ) {
        val failures = mutableListOf<Throwable>()
        val dao = app.container.profileDatabase.profileDao()
        val checkpointIds = profileCheckpoint.mapTo(mutableSetOf()) { profile -> profile.id }
        val createdIds = dao.getAllProfiles().mapTo(mutableSetOf()) { profile -> profile.id } - checkpointIds
        createdIds.forEach { profileId ->
            failures.captureCleanupFailure {
                app.container.profileRepository.deleteProfile(profileId)
            }
        }
        profileCheckpoint.firstOrNull { profile -> profile.isActive }?.id?.let { activeProfileId ->
            failures.captureCleanupFailure {
                app.container.profileRepository.setActiveProfile(activeProfileId)
            }
        }
        val primary = failures.firstOrNull() ?: return
        failures.drop(1).forEach(primary::addSuppressed)
        throw primary
    }

    private suspend fun MutableList<Throwable>.captureCleanupFailure(action: suspend () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            add(error)
        }
    }

    private fun propagateCleanupFailures(
        primaryFailure: Throwable?,
        cleanupFailures: List<Throwable>,
    ) {
        if (primaryFailure != null) {
            cleanupFailures.filterNot { cleanupFailure -> cleanupFailure === primaryFailure }
                .forEach(primaryFailure::addSuppressed)
            return
        }

        val cleanupPrimary = cleanupFailures.firstOrNull() ?: return
        cleanupFailures.drop(1)
            .filterNot { cleanupFailure -> cleanupFailure === cleanupPrimary }
            .forEach(cleanupPrimary::addSuppressed)
        throw cleanupPrimary
    }

    private companion object {
        const val ARG_SENTINEL_QUARANTINE = "foxhole.liveSentinelQuarantine"
        const val ARG_DNS_FILTER = "foxhole.liveDnsFilterEnforcement"
        const val DEBUG_APPLICATION_SUFFIX = ".debug"
        const val LOCAL_GUARD_START_TIMEOUT_MS = 45_000L
        const val PROFILE_START_TIMEOUT_MS = 150_000L
        const val QUARANTINE_APPLY_TIMEOUT_MS = 45_000L
        const val RUNTIME_RELOAD_TIMEOUT_MS = 90_000L
        const val RUNTIME_STOP_TIMEOUT_MS = 30_000L
        const val DNS_QUERY_TIMEOUT_MS = 8_000L
        const val DNS_COUNTER_TIMEOUT_MS = 8_000L
        const val TEST_DNS_SERVER = "1.1.1.1"

        val DIRECT_EXECUTOR = Executor { command -> command.run() }

        const val ALLOWED_CONTROL = "example.com"
        val BLOCKED_PROBE_CANDIDATES =
            listOf(
                "pagead2.googlesyndication.com",
                "googleads.g.doubleclick.net",
                "securepubads.g.doubleclick.net",
                "ad.doubleclick.net",
                "ads.yahoo.com",
                "ib.adnxs.com",
                "adsrvr.org",
                "adservice.google.com",
            )
    }

    private data class DnsProfileTarget(
        val profile: Profile,
        val target: RuntimeProbeTarget,
    )
}
