package com.foxhole.guard

import android.content.Context
import android.net.ConnectivityManager
import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.WebAppRoute
import com.foxhole.core.model.effectiveDiagnosticsRetention
import com.foxhole.core.runtime.AndroidLanProxyAddressProvider
import com.foxhole.core.runtime.ConnectivityNetworkRegistry
import com.foxhole.core.runtime.GeoIpDatabaseStore
import com.foxhole.core.runtime.I2pdManager
import com.foxhole.core.runtime.I2pdProcessManager
import com.foxhole.core.runtime.I2pdRuntimeInstaller
import com.foxhole.core.runtime.LanProxyAddressProvider
import com.foxhole.core.runtime.RuntimeConfigAssembler
import com.foxhole.core.runtime.RuntimeDnsRuleSetInstallOutcome
import com.foxhole.core.runtime.RuntimeInstanceStore
import com.foxhole.core.runtime.RuntimeKillResult
import com.foxhole.core.runtime.RuntimeSupervisor
import com.foxhole.core.runtime.TorBridgeStore
import com.foxhole.core.runtime.TorGeoIpCountryResolver
import com.foxhole.core.runtime.TorRuntimeInstaller
import com.foxhole.core.runtime.createVpnRuntime
import com.foxhole.core.runtime.network.BoundedSystemHostResolver
import com.foxhole.core.runtime.network.IpInfoRepository
import com.foxhole.core.runtime.network.NetworkBoundPublicDnsFallback
import com.foxhole.core.runtime.network.NetworkFingerprintProvider
import com.foxhole.core.runtime.network.PublicRemoteDns
import com.foxhole.core.runtime.preferredNonVpnInternetNetwork
import com.foxhole.core.runtime.tunnelRuntimeProxyAccess
import com.foxhole.guard.core.data.EncryptedProfileSecretStore
import com.foxhole.guard.core.data.I2pTrafficRepository
import com.foxhole.guard.core.data.LocalDataRepository
import com.foxhole.guard.core.data.ProfileDatabase
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.core.data.RoutingRepository
import com.foxhole.guard.core.data.WebAppsRepository
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import com.foxhole.guard.core.security.FoxholeSecurityComponents
import com.foxhole.guard.core.security.KeyboxDatabaseKeySource
import com.foxhole.guard.core.security.KeystoreDatabaseKeySource
import com.foxhole.guard.core.security.vault.FoxholeVault
import com.foxhole.guard.core.sentinel.AppNetworkCategoryResolver
import com.foxhole.guard.core.sentinel.SentinelThreatIntelProvider
import com.foxhole.guard.core.sentinel.anomaly.AnomalyRepository
import com.foxhole.guard.core.sentinel.anomaly.SentinelDetectionNotifier
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.asRuntimeSettings
import com.foxhole.guard.core.webapps.WebAppProxyController
import com.foxhole.guard.core.webapps.WebAppProxyPlan
import com.foxhole.guard.core.webapps.WebAppsDataCleaner
import com.foxhole.guard.core.webapps.WebAppsNotifier
import com.foxhole.guard.core.webapps.WebAppsWatchdog
import com.foxhole.guard.runtime.AppUpdateApkVerifier
import com.foxhole.guard.runtime.AppUpdateClient
import com.foxhole.guard.runtime.AppUpdateRepository
import com.foxhole.guard.runtime.DnsFilterAssetInstaller
import com.foxhole.guard.runtime.DnsFilterUpdateClient
import com.foxhole.guard.runtime.DnsFilterUpdateRepository
import com.foxhole.guard.runtime.FileThreatIntelStore
import com.foxhole.guard.runtime.FileTlsFingerprintStore
import com.foxhole.guard.runtime.FoxholeConnectionController
import com.foxhole.guard.runtime.GeoIpUpdateClient
import com.foxhole.guard.runtime.GeoIpUpdateRepository
import com.foxhole.guard.runtime.QuarantineRuntimeEnforcementTracker
import com.foxhole.guard.runtime.ThreatIntelUpdateClient
import com.foxhole.guard.runtime.ThreatIntelUpdateRepository
import com.foxhole.guard.runtime.TlsFingerprintProvider
import com.foxhole.guard.runtime.TlsFingerprintUpdateClient
import com.foxhole.guard.runtime.TlsFingerprintUpdateRepository
import com.foxhole.guard.runtime.TorBridgeUpdateClient
import com.foxhole.guard.runtime.TorBridgeUpdateRepository
import com.foxhole.guard.runtime.enqueueQuarantineRuntimeEnforcement
import com.foxhole.guard.runtime.foxholeDbBridgesManifestUrl
import com.foxhole.guard.runtime.foxholeDbGeoIpManifestUrl
import com.foxhole.guard.runtime.foxholeDbThreatIntelManifestUrl
import com.foxhole.guard.runtime.foxholeDbTlsFingerprintsManifestUrl
import com.foxhole.guard.runtime.tlsFingerprintProvider
import com.foxhole.guard.traffic.AndroidTrafficMapCountryRegistryProvider
import com.foxhole.guard.traffic.TrafficMapRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

internal class FoxholeCoreGraphModule(
    private val appContext: Context,
) {
    val json: Json by lazy {
        Json {
            prettyPrint = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(
            context = appContext,
            onSecurityAnalysisFallback = { _, error ->

                diagnosticsLogger.record(
                    "app-inventory",
                    "security analysis fell back to blank facts: ${error.javaClass.simpleName}",
                )
            },
            onQuarantinePolicyRevisionChanged = appContext::enqueueQuarantineRuntimeEnforcement,
        )
    }

    private val trafficMapCountryRegistryProvider: AndroidTrafficMapCountryRegistryProvider by lazy {
        AndroidTrafficMapCountryRegistryProvider(appContext)
    }

    val trafficMapRepository: TrafficMapRepository by lazy {
        TrafficMapRepository(
            countryRegistryProvider = trafficMapCountryRegistryProvider::registry,
            ownPackageName = appContext.packageName,
        )
    }

    val sentinelDetectionNotifier: SentinelDetectionNotifier by lazy {
        SentinelDetectionNotifier(appContext)
    }

    val webAppsNotifier: WebAppsNotifier by lazy { WebAppsNotifier(appContext) }

    val webAppsDataCleaner: WebAppsDataCleaner by lazy { WebAppsDataCleaner(appContext) }

    val foxholeVault: FoxholeVault by lazy { FoxholeVault(appContext) }

    val diagnosticsLogger: DiagnosticsLogger by lazy {
        DiagnosticsLogger(
            context = appContext,
            retentionProvider = { settingsRepository.settings.value.expert.effectiveDiagnosticsRetention() },
            settingsSnapshotProvider = { settingsRepository.settings.value },
        )
    }
}

internal class FoxholeDataGraphModule(
    private val appContext: Context,
    private val core: FoxholeCoreGraphModule,
    private val security: () -> FoxholeSecurityComponents,
    private val liveDnsRuleSetInstaller: suspend (
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
    ) -> RuntimeDnsRuleSetInstallOutcome,
) {
    val profileDatabase: ProfileDatabase by lazy {
        val components = security()
        val keySource =
            if (components.isPasswordProtectionActive()) {
                KeyboxDatabaseKeySource(components.secureSessionHolder)
            } else {
                KeystoreDatabaseKeySource(appContext, keyboxExists = components::keyboxExists)
            }
        ProfileDatabase.create(appContext, keySource)
    }
    val secretStore: EncryptedProfileSecretStore by lazy { EncryptedProfileSecretStore(appContext, core.json) }

    private val connectivityManager by lazy {
        appContext.getSystemService(ConnectivityManager::class.java)
            ?: error("missing connectivity manager")
    }

    private fun currentUnderlyingNetwork() =
        connectivityManager.preferredNonVpnInternetNetwork(
            candidates = ConnectivityNetworkRegistry.snapshot(appContext),
        )

    private val publicRemoteDns: PublicRemoteDns by lazy {
        PublicRemoteDns(
            delegate = BoundedSystemHostResolver(
                delegate = { hostname ->
                    currentUnderlyingNetwork()?.getAllByName(hostname)?.toList()
                        ?: core.httpClient.dns.lookup(hostname)
                },
            ),
            fallback = NetworkBoundPublicDnsFallback(::currentUnderlyingNetwork),
        )
    }
    private val publicRemoteHttpClient by lazy {
        core.httpClient.newBuilder().dns(publicRemoteDns).build()
    }
    val importParser: ProfileImportParser by lazy {
        ProfileImportParser(core.json, remoteHostResolver = publicRemoteDns::lookup)
    }
    private val databaseReadyGate: suspend () -> Unit = {
        val components = security()
        if (components.isDatabaseLockedForBackground()) {
            components.dataKeyAvailable.first { available -> available }
        }
    }

    val routingRepository: RoutingRepository by lazy {
        RoutingRepository(
            { profileDatabase },
            core.httpClient,
            core.json,
            awaitDatabaseReady = databaseReadyGate,
        )
    }
    val torBridgeStore: TorBridgeStore by lazy { TorBridgeStore(appContext, core.json) }
    val torRuntimeInstaller: TorRuntimeInstaller by lazy {
        TorRuntimeInstaller(
            appContext,
            DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
            torBridgeStore,
        )
    }
    val i2pdRuntimeInstaller: I2pdRuntimeInstaller by lazy {
        I2pdRuntimeInstaller(appContext, DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger))
    }
    val i2pdManager: I2pdManager by lazy {
        I2pdProcessManager(
            installer = i2pdRuntimeInstaller,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
            isMeteredNetwork = {
                runCatching {
                    appContext.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == true
                }.getOrDefault(false)
            },
        )
    }

    private val foxholeDbBase: () -> String = {
        core.settingsRepository.settings.value.updateSources.databaseBaseUrl
    }
    val torBridgeUpdateClient: TorBridgeUpdateClient by lazy {
        TorBridgeUpdateClient(
            core.httpClient,
            resolver = publicRemoteDns::lookup,
            manifestUrl = { foxholeDbBridgesManifestUrl(foxholeDbBase()) },
            context = appContext,
        )
    }
    val torBridgeUpdateRepository: TorBridgeUpdateRepository by lazy {
        TorBridgeUpdateRepository(
            settingsRepository = core.settingsRepository,
            client = torBridgeUpdateClient,
            store = torBridgeStore,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
        )
    }
    val dnsFilterAssetInstaller: DnsFilterAssetInstaller by lazy { DnsFilterAssetInstaller(appContext, core.json) }
    val dnsFilterUpdateClient: DnsFilterUpdateClient by lazy {
        DnsFilterUpdateClient(core.httpClient, core.json, resolver = publicRemoteDns::lookup)
    }
    val dnsFilterUpdateRepository: DnsFilterUpdateRepository by lazy {
        DnsFilterUpdateRepository(
            settingsRepository = core.settingsRepository.asRuntimeSettings(),
            client = dnsFilterUpdateClient,
            store = dnsFilterAssetInstaller,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
            installedManifestProvider = { dnsFilterAssetInstaller.installedManifestOrNull() },
            installedRuleSetsProvider = { dnsFilterAssetInstaller.installedRuleSetsOrEmpty() },
            liveRuleSetInstaller = liveDnsRuleSetInstaller,
        )
    }
    val appUpdateClient: AppUpdateClient by lazy {
        AppUpdateClient(
            core.httpClient,
            resolver = publicRemoteDns::lookup,
            releasesApiUrl = { core.settingsRepository.settings.value.updateSources.appReleasesUrl },
            releasesToken = { core.settingsRepository.settings.value.updateSources.appReleasesToken },
        )
    }
    val appUpdateRepository: AppUpdateRepository by lazy {
        val verifier = AppUpdateApkVerifier(appContext)
        AppUpdateRepository(
            client = appUpdateClient,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            downloadDirectory = File(appContext.cacheDir, "app-update"),
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
            apkVerifier = verifier::verify,
        )
    }

    val geoIpDatabaseStore: GeoIpDatabaseStore by lazy { GeoIpDatabaseStore(appContext, core.json) }
    val geoIpUpdateClient: GeoIpUpdateClient by lazy {
        GeoIpUpdateClient(
            appContext,
            core.httpClient,
            core.json,
            resolver = publicRemoteDns::lookup,
            manifestUrl = { foxholeDbGeoIpManifestUrl(foxholeDbBase()) },
        )
    }
    val geoIpUpdateRepository: GeoIpUpdateRepository by lazy {
        GeoIpUpdateRepository(
            client = geoIpUpdateClient,
            store = geoIpDatabaseStore,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
        )
    }
    val fileThreatIntelStore: FileThreatIntelStore by lazy { FileThreatIntelStore(appContext, core.json) }
    val threatIntelUpdateClient: ThreatIntelUpdateClient by lazy {
        ThreatIntelUpdateClient(core.httpClient, core.json, resolver = publicRemoteDns::lookup)
    }
    val threatIntelUpdateRepository: ThreatIntelUpdateRepository by lazy {
        ThreatIntelUpdateRepository(
            client = threatIntelUpdateClient,
            store = fileThreatIntelStore,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
            manifestUrl = { foxholeDbThreatIntelManifestUrl(foxholeDbBase()) },
        )
    }
    val fileTlsFingerprintStore: FileTlsFingerprintStore by lazy { FileTlsFingerprintStore(appContext, core.json) }
    val tlsFingerprintProvider: TlsFingerprintProvider by lazy {
        tlsFingerprintProvider(appContext, fileTlsFingerprintStore, core.json)
    }
    val tlsFingerprintUpdateClient: TlsFingerprintUpdateClient by lazy {
        TlsFingerprintUpdateClient(core.httpClient, core.json, resolver = publicRemoteDns::lookup)
    }
    val tlsFingerprintUpdateRepository: TlsFingerprintUpdateRepository by lazy {
        TlsFingerprintUpdateRepository(
            client = tlsFingerprintUpdateClient,
            store = fileTlsFingerprintStore,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
            manifestUrl = { foxholeDbTlsFingerprintsManifestUrl(foxholeDbBase()) },
        )
    }

    val lanProxyAddressProvider: LanProxyAddressProvider by lazy {
        AndroidLanProxyAddressProvider(appContext)
    }

    val runtimeConfigAssembler: RuntimeConfigAssembler by lazy {
        RuntimeConfigAssembler(
            core.json,
            lanProxyAddressProvider,

            selfPackageName = appContext.packageName,
        )
    }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(
            databaseProvider = { profileDatabase },
            secretStore = secretStore,
            parser = importParser,
            httpClient = publicRemoteHttpClient,
            diagnosticsLogger = core.diagnosticsLogger,
            settingsRepository = core.settingsRepository,
            routingRepository = routingRepository,
            runtimeConfigAssembler = runtimeConfigAssembler,
            torRuntimeInstaller = torRuntimeInstaller,
            i2pdManager = i2pdManager,
            dnsFilterAssetInstaller = dnsFilterAssetInstaller,
            json = core.json,
            remoteHostResolver = publicRemoteDns::lookup,
            underlyingSocketFactory = { currentUnderlyingNetwork()?.socketFactory },
            awaitDatabaseReady = databaseReadyGate,
        )
    }

    val anomalyRepository: AnomalyRepository by lazy {
        AnomalyRepository(
            daoProvider = { profileDatabase.anomalyDao() },
            settingsRepository = core.settingsRepository,
            diagnosticsLogger = core.diagnosticsLogger,
            notifier = core.sentinelDetectionNotifier,
            networkIocMatcher = SentinelThreatIntelProvider(appContext)::networkIocMatcher,
            appCategoryResolver = AppNetworkCategoryResolver(appContext)::categoryOf,
            awaitDatabaseReady = databaseReadyGate,
        )
    }

    val i2pTrafficRepository: I2pTrafficRepository by lazy {
        I2pTrafficRepository(
            daoProvider = { profileDatabase.i2pTrafficDao() },
            awaitDatabaseReady = databaseReadyGate,
        )
    }

    val webAppsRepository: WebAppsRepository by lazy {
        WebAppsRepository(
            context = appContext,
            daoProvider = { profileDatabase.webAppDao() },
            httpClient = core.httpClient,
            awaitDatabaseReady = databaseReadyGate,
        )
    }

    val localDataRepository: LocalDataRepository by lazy {
        LocalDataRepository(
            context = appContext,
            databaseProvider = { profileDatabase },
            settingsRepository = core.settingsRepository,
            diagnosticsLogger = core.diagnosticsLogger,
            profileSecretStore = secretStore,
            dnsFilterAssetInstaller = dnsFilterAssetInstaller,
        )
    }
}

internal class FoxholeRuntimeGraphModule(
    private val appContext: Context,
    private val core: FoxholeCoreGraphModule,
    private val data: FoxholeDataGraphModule,
) {
    private val localGeoIpResolver by lazy { TorGeoIpCountryResolver(appContext) }
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val webAppsWatchdog: WebAppsWatchdog by lazy {
        WebAppsWatchdog(
            context = appContext,
            settingsRepository = core.settingsRepository,
            webAppsRepository = data.webAppsRepository,
            scope = runtimeScope,
            routeReady = { route ->
                when (route) {
                    com.foxhole.core.model.WebAppRoute.DIRECT -> true
                    com.foxhole.core.model.WebAppRoute.BLOCK -> false
                    else -> connectionController.isConnectedRuntimeCurrent()
                }
            },
            proxyController = webAppProxyController,
            recordDiagnostic = { message -> core.diagnosticsLogger.record("web_apps", message) },
            onBadgeIncreased = { app, count, content ->
                core.webAppsNotifier.notify(app, count, content)
            },
        )
    }

    val webAppProxyController: WebAppProxyController by lazy {
        WebAppProxyController(appContext) { route, blockWithoutTunnel ->
            webAppProxyPlan(route, blockWithoutTunnel)
        }
    }

    private suspend fun webAppProxyPlan(
        route: WebAppRoute,
        blockWithoutTunnel: Boolean,
    ): WebAppProxyPlan? =
        when (route) {
            WebAppRoute.BLOCK -> null
            WebAppRoute.DIRECT -> WebAppProxyPlan.Direct
            WebAppRoute.DEFAULT ->
                if (blockWithoutTunnel) tunnelWebAppProxyPlan() else WebAppProxyPlan.Direct
            WebAppRoute.VPN -> tunnelWebAppProxyPlan()

            WebAppRoute.TOR, WebAppRoute.I2P -> null
        }

    private suspend fun tunnelWebAppProxyPlan(): WebAppProxyPlan? {
        if (!connectionController.isConnectedRuntimeCurrent()) return null
        return WebAppProxyPlan.Http(core.settingsRepository.current().tunnelRuntimeProxyAccess())
    }

    val ipCountryCodeResolver: (String) -> String? get() = localGeoIpResolver::countryCodeForIpAddress

    val ipInfoRepository: IpInfoRepository by lazy {
        IpInfoRepository(
            client = core.httpClient,
            json = core.json,
            localCountryCode = localGeoIpResolver::countryCodeForIpAddress,
        )
    }
    val networkFingerprintProvider: NetworkFingerprintProvider by lazy { NetworkFingerprintProvider(appContext) }
    val runtimeInstanceStore: RuntimeInstanceStore by lazy {
        RuntimeInstanceStore {
            createVpnRuntime(
                diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
            )
        }
    }
    val runtimeSupervisor: RuntimeSupervisor by lazy {
        RuntimeSupervisor(
            scope = runtimeScope,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
            emergencyKill = { reason ->
                runtimeInstanceStore.current()?.forceKill(reason)
                    ?: RuntimeKillResult(
                        reason = reason,
                        tunClosed = true,
                        serverDetached = false,
                    )
            },
        )
    }
    val quarantineEnforcementTracker: QuarantineRuntimeEnforcementTracker by lazy {
        QuarantineRuntimeEnforcementTracker()
    }

    val connectionController: FoxholeConnectionController by lazy {
        FoxholeConnectionController(
            context = appContext,
            profileRepository = data.profileRepository,
            settingsRepository = core.settingsRepository,
            routingRepository = data.routingRepository,
            ipInfoRepository = ipInfoRepository,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
            runtimeConfigAssembler = data.runtimeConfigAssembler,
            runtimeInstanceStore = runtimeInstanceStore,
            runtimeSupervisor = runtimeSupervisor,
            quarantineEnforcementTracker = quarantineEnforcementTracker,

            ownMasterTunFd = { runtimeInstanceStore.nativeSnapshot().masterTunFd },
        )
    }
}
