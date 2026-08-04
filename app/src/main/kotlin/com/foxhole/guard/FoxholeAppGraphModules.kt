package com.foxhole.guard
import android.content.Context
import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.effectiveDiagnosticsRetention
import com.foxhole.core.runtime.AndroidLanProxyAddressProvider
import com.foxhole.core.runtime.GeoIpDatabaseStore
import com.foxhole.core.runtime.I2pdManager
import com.foxhole.core.runtime.I2pdProcessManager
import com.foxhole.core.runtime.I2pdRuntimeInstaller
import com.foxhole.core.runtime.RuntimeConfigAssembler
import com.foxhole.core.runtime.RuntimeInstanceStore
import com.foxhole.core.runtime.RuntimeKillResult
import com.foxhole.core.runtime.RuntimeSupervisor
import com.foxhole.core.runtime.TorBridgeStore
import com.foxhole.core.runtime.TorGeoIpCountryResolver
import com.foxhole.core.runtime.TorRuntimeInstaller
import com.foxhole.core.runtime.createVpnRuntime
import com.foxhole.core.runtime.network.BoundedSystemHostResolver
import com.foxhole.core.runtime.network.IpInfoRepository
import com.foxhole.core.runtime.network.NetworkFingerprintProvider
import com.foxhole.core.runtime.network.PublicRemoteDns
import com.foxhole.guard.core.data.EncryptedProfileSecretStore
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
import com.foxhole.guard.core.security.vault.WebAppCredentialsStore
import com.foxhole.guard.core.sentinel.AppNetworkCategoryResolver
import com.foxhole.guard.core.sentinel.anomaly.AnomalyNotifier
import com.foxhole.guard.core.sentinel.anomaly.AnomalyRepository
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.asRuntimeSettings
import com.foxhole.guard.core.webapps.WebAppsNotifier
import com.foxhole.guard.core.webapps.WebAppsWatchdog
import com.foxhole.guard.runtime.AppUpdateClient
import com.foxhole.guard.runtime.AppUpdateRepository
import com.foxhole.guard.runtime.DnsFilterAssetInstaller
import com.foxhole.guard.runtime.DnsFilterUpdateClient
import com.foxhole.guard.runtime.DnsFilterUpdateRepository
import com.foxhole.guard.runtime.FileThreatIntelStore
import com.foxhole.guard.runtime.FoxholeConnectionController
import com.foxhole.guard.runtime.GeoIpUpdateClient
import com.foxhole.guard.runtime.GeoIpUpdateRepository
import com.foxhole.guard.runtime.ThreatIntelUpdateClient
import com.foxhole.guard.runtime.ThreatIntelUpdateRepository
import com.foxhole.guard.runtime.TorBridgeUpdateClient
import com.foxhole.guard.runtime.TorBridgeUpdateRepository
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
        SettingsRepository(appContext) { _, error ->
            // Package name deliberately omitted from diagnostics; only the failure class matters.
            diagnosticsLogger.record(
                "app-inventory",
                "security analysis fell back to blank facts: ${error.javaClass.simpleName}",
            )
        }
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

    val anomalyNotifier: AnomalyNotifier by lazy { AnomalyNotifier(appContext) }

    val webAppsNotifier: WebAppsNotifier by lazy { WebAppsNotifier(appContext) }

    val foxholeVault: FoxholeVault by lazy { FoxholeVault(appContext) }

    val webAppCredentialsStore: WebAppCredentialsStore by lazy { WebAppCredentialsStore(foxholeVault) }

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
) {
    val profileDatabase: ProfileDatabase by lazy {
        // The DB opens lazily; in password mode every pre-unlock touch is gated, so by the
        // time this runs the session holds the dataKey. Otherwise the Keystore source serves
        // the passphrase (and refuses to mint a new one while a keybox exists - fail closed).
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

    // A budgeted resolver: this instance is also used outside an OkHttp call — by the resolved-config
    // sanitiser on the connect path — where no call timeout covers it.
    private val publicRemoteDns: PublicRemoteDns by lazy {
        PublicRemoteDns(BoundedSystemHostResolver(core.httpClient.dns::lookup))
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
    val torBridgeUpdateClient: TorBridgeUpdateClient by lazy {
        TorBridgeUpdateClient(core.httpClient, resolver = publicRemoteDns::lookup)
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
        )
    }
    val appUpdateClient: AppUpdateClient by lazy {
        AppUpdateClient(core.httpClient, resolver = publicRemoteDns::lookup)
    }
    val appUpdateRepository: AppUpdateRepository by lazy {
        AppUpdateRepository(
            client = appUpdateClient,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            downloadDirectory = File(appContext.cacheDir, "app-update"),
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
        )
    }

    val geoIpDatabaseStore: GeoIpDatabaseStore by lazy { GeoIpDatabaseStore(appContext, core.json) }
    val geoIpUpdateClient: GeoIpUpdateClient by lazy {
        GeoIpUpdateClient(appContext, core.httpClient, core.json, resolver = publicRemoteDns::lookup)
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
        )
    }
    val runtimeConfigAssembler: RuntimeConfigAssembler by lazy {
        RuntimeConfigAssembler(
            core.json,
            AndroidLanProxyAddressProvider(appContext),
            // The RUNNING package, not the release id baked into BuildConfig: the guard's own
            // split has to exclude whichever variant is installed.
            selfPackageName = appContext.packageName,
        )
    }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(
            databaseProvider = { profileDatabase },
            secretStore = secretStore,
            parser = importParser,
            httpClient = core.httpClient,
            diagnosticsLogger = core.diagnosticsLogger,
            settingsRepository = core.settingsRepository,
            routingRepository = routingRepository,
            runtimeConfigAssembler = runtimeConfigAssembler,
            torRuntimeInstaller = torRuntimeInstaller,
            i2pdManager = i2pdManager,
            dnsFilterAssetInstaller = dnsFilterAssetInstaller,
            json = core.json,
            awaitDatabaseReady = databaseReadyGate,
        )
    }

    val anomalyRepository: AnomalyRepository by lazy {
        AnomalyRepository(
            daoProvider = { profileDatabase.anomalyDao() },
            settingsRepository = core.settingsRepository,
            diagnosticsLogger = core.diagnosticsLogger,
            notifier = core.anomalyNotifier,
            appCategoryResolver = AppNetworkCategoryResolver(appContext)::categoryOf,
            awaitDatabaseReady = databaseReadyGate,
        )
    }

    val webAppsRepository: WebAppsRepository by lazy {
        WebAppsRepository(
            context = appContext,
            daoProvider = { profileDatabase.webAppDao() },
            httpClient = core.httpClient,
            awaitDatabaseReady = databaseReadyGate,
            onWebAppRemoved = { id -> core.webAppCredentialsStore.delete(id) },
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
            routeReady = { connectionController.isConnectedRuntimeCurrent() },
            onBadgeIncreased = { app, count -> core.webAppsNotifier.notify(app, count) },
        )
    }

    // Offline IP -> ISO country lookup for UI accents (e.g. the DNS-server flag on the network
    // card). Loads the geo database lazily on first use - call off the main thread.
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

    val connectionController: FoxholeConnectionController by lazy {
        FoxholeConnectionController(
            context = appContext,
            profileRepository = data.profileRepository,
            settingsRepository = core.settingsRepository,
            routingRepository = data.routingRepository,
            ipInfoRepository = ipInfoRepository,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(core.diagnosticsLogger),
            runtimeConfigAssembler = data.runtimeConfigAssembler,
        )
    }
}
