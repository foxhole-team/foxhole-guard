package com.foxhole.beta

import android.content.Context
import com.foxhole.beta.core.anomaly.AnomalyNotifier
import com.foxhole.beta.core.anomaly.AnomalyRepository
import com.foxhole.beta.core.data.EncryptedProfileSecretStore
import com.foxhole.beta.core.data.ProfileDatabase
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.importer.ProfileImportParser
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.network.NetworkFingerprintProvider
import com.foxhole.beta.core.network.PublicRemoteDns
import com.foxhole.beta.core.settings.SettingsRepository
import com.foxhole.beta.core.traffic.LibboxTrafficMapConnectionSource
import com.foxhole.beta.core.traffic.TrafficMapRepository
import com.foxhole.beta.vpn.AndroidLanProxyAddressProvider
import com.foxhole.beta.vpn.DnsFilterAssetInstaller
import com.foxhole.beta.vpn.DnsFilterUpdateClient
import com.foxhole.beta.vpn.DnsFilterUpdateRepository
import com.foxhole.beta.vpn.FoxholeConnectionController
import com.foxhole.beta.vpn.RuntimeConfigAssembler
import com.foxhole.beta.vpn.TorManager
import com.foxhole.beta.vpn.TorProcessManager
import com.foxhole.beta.vpn.TorRuntimeInstaller
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

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

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val trafficMapRepository: TrafficMapRepository by lazy {
        TrafficMapRepository(LibboxTrafficMapConnectionSource(appContext))
    }

    val anomalyNotifier: AnomalyNotifier by lazy { AnomalyNotifier(appContext) }

    val diagnosticsLogger: DiagnosticsLogger by lazy {
        DiagnosticsLogger(
            context = appContext,
            retentionProvider = { settingsRepository.settings.value.expert.diagnosticsRetention },
            settingsSnapshotProvider = { settingsRepository.settings.value },
        )
    }
}

internal class FoxholeDataGraphModule(
    private val appContext: Context,
    private val core: FoxholeCoreGraphModule,
) {
    val profileDatabase: ProfileDatabase by lazy { ProfileDatabase.create(appContext) }
    val secretStore: EncryptedProfileSecretStore by lazy { EncryptedProfileSecretStore(appContext, core.json) }
    private val publicRemoteDns: PublicRemoteDns by lazy { PublicRemoteDns(core.httpClient.dns::lookup) }
    val importParser: ProfileImportParser by lazy {
        ProfileImportParser(core.json, remoteHostResolver = publicRemoteDns::lookup)
    }
    val routingRepository: RoutingRepository by lazy { RoutingRepository({ profileDatabase }, core.httpClient, core.json) }
    val torRuntimeInstaller: TorRuntimeInstaller by lazy { TorRuntimeInstaller(appContext, core.diagnosticsLogger) }
    val torManager: TorManager by lazy { TorProcessManager(torRuntimeInstaller, core.diagnosticsLogger) }
    val dnsFilterAssetInstaller: DnsFilterAssetInstaller by lazy { DnsFilterAssetInstaller(appContext, core.json) }
    val dnsFilterUpdateClient: DnsFilterUpdateClient by lazy {
        DnsFilterUpdateClient(core.httpClient, core.json, resolver = publicRemoteDns::lookup)
    }
    val dnsFilterUpdateRepository: DnsFilterUpdateRepository by lazy {
        DnsFilterUpdateRepository(
            settingsRepository = core.settingsRepository,
            client = dnsFilterUpdateClient,
            store = dnsFilterAssetInstaller,
            diagnosticsLogger = core.diagnosticsLogger,
        )
    }
    val runtimeConfigAssembler: RuntimeConfigAssembler by lazy {
        RuntimeConfigAssembler(core.json, AndroidLanProxyAddressProvider(appContext))
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
            torManager = torManager,
            dnsFilterAssetInstaller = dnsFilterAssetInstaller,
            json = core.json,
        )
    }

    val anomalyRepository: AnomalyRepository by lazy {
        AnomalyRepository(
            daoProvider = { profileDatabase.anomalyDao() },
            settingsRepository = core.settingsRepository,
            diagnosticsLogger = core.diagnosticsLogger,
            notifier = core.anomalyNotifier,
        )
    }
}

internal class FoxholeRuntimeGraphModule(
    private val appContext: Context,
    private val core: FoxholeCoreGraphModule,
    private val data: FoxholeDataGraphModule,
) {
    val ipInfoRepository: IpInfoRepository by lazy { IpInfoRepository(core.httpClient, core.json) }
    val networkFingerprintProvider: NetworkFingerprintProvider by lazy { NetworkFingerprintProvider(appContext) }

    val connectionController: FoxholeConnectionController by lazy {
        FoxholeConnectionController(
            context = appContext,
            profileRepository = data.profileRepository,
            settingsRepository = core.settingsRepository,
            routingRepository = data.routingRepository,
            ipInfoRepository = ipInfoRepository,
            diagnosticsLogger = core.diagnosticsLogger,
            runtimeConfigAssembler = data.runtimeConfigAssembler,
        )
    }
}
