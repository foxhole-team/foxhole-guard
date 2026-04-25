package com.foxhole.beta

import android.content.Context
import com.foxhole.beta.core.data.EncryptedProfileSecretStore
import com.foxhole.beta.core.data.ProfileDatabase
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.importer.ProfileImportParser
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.network.NetworkFingerprintProvider
import com.foxhole.beta.core.settings.SettingsRepository
import com.foxhole.beta.vpn.AndroidLanProxyAddressProvider
import com.foxhole.beta.vpn.FoxholeConnectionController
import com.foxhole.beta.vpn.RuntimeConfigAssembler
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
    val importParser: ProfileImportParser by lazy { ProfileImportParser(core.json) }
    val routingRepository: RoutingRepository by lazy { RoutingRepository(profileDatabase, core.httpClient, core.json) }
    val runtimeConfigAssembler: RuntimeConfigAssembler by lazy {
        RuntimeConfigAssembler(core.json, AndroidLanProxyAddressProvider(appContext))
    }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(
            database = profileDatabase,
            secretStore = secretStore,
            parser = importParser,
            httpClient = core.httpClient,
            diagnosticsLogger = core.diagnosticsLogger,
            settingsRepository = core.settingsRepository,
            routingRepository = routingRepository,
            runtimeConfigAssembler = runtimeConfigAssembler,
            json = core.json,
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
