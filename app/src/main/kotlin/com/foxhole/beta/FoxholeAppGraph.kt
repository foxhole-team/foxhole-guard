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

interface FoxholeStartupDependencies {
    val settingsRepository: SettingsRepository
    val profileRepository: ProfileRepository
    val diagnosticsLogger: DiagnosticsLogger
}

interface FoxholeHomeDependencies {
    val settingsRepository: SettingsRepository
    val profileRepository: ProfileRepository
    val routingRepository: RoutingRepository
    val connectionController: FoxholeConnectionController
    val diagnosticsLogger: DiagnosticsLogger
    val networkFingerprintProvider: NetworkFingerprintProvider
    val runtimeConfigAssembler: RuntimeConfigAssembler
}

interface FoxholeRuntimeDependencies {
    val settingsRepository: SettingsRepository
    val profileRepository: ProfileRepository
    val diagnosticsLogger: DiagnosticsLogger
    val networkFingerprintProvider: NetworkFingerprintProvider
    val connectionController: FoxholeConnectionController
    val ipInfoRepository: IpInfoRepository
}

interface FoxholeTileDependencies {
    val settingsRepository: SettingsRepository
}

interface FoxholeRefreshWorkerDependencies {
    val profileRepository: ProfileRepository
    val diagnosticsLogger: DiagnosticsLogger
}

interface FoxholeDiagnosticsDependencies {
    val diagnosticsLogger: DiagnosticsLogger
}

class FoxholeAppGraph(
    context: Context,
) : FoxholeStartupDependencies, FoxholeHomeDependencies, FoxholeRuntimeDependencies, FoxholeTileDependencies, FoxholeRefreshWorkerDependencies, FoxholeDiagnosticsDependencies {
    private val appContext = context.applicationContext
    private val json by lazy {
        Json {
            prettyPrint = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    override val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    override val diagnosticsLogger: DiagnosticsLogger by lazy {
        DiagnosticsLogger(
            context = appContext,
            retentionProvider = { settingsRepository.settings.value.expert.diagnosticsRetention },
            settingsSnapshotProvider = { settingsRepository.settings.value },
        )
    }
    val profileDatabase: ProfileDatabase by lazy { ProfileDatabase.create(appContext) }
    val secretStore: EncryptedProfileSecretStore by lazy { EncryptedProfileSecretStore(appContext, json) }
    val importParser: ProfileImportParser by lazy { ProfileImportParser(json) }
    override val routingRepository: RoutingRepository by lazy { RoutingRepository(profileDatabase, httpClient, json) }
    override val runtimeConfigAssembler: RuntimeConfigAssembler by lazy {
        RuntimeConfigAssembler(json, AndroidLanProxyAddressProvider(appContext))
    }
    override val ipInfoRepository: IpInfoRepository by lazy { IpInfoRepository(httpClient, json) }
    override val networkFingerprintProvider: NetworkFingerprintProvider by lazy { NetworkFingerprintProvider(appContext) }
    override val profileRepository: ProfileRepository by lazy {
        ProfileRepository(
            database = profileDatabase,
            secretStore = secretStore,
            parser = importParser,
            httpClient = httpClient,
            diagnosticsLogger = diagnosticsLogger,
            settingsRepository = settingsRepository,
            routingRepository = routingRepository,
            runtimeConfigAssembler = runtimeConfigAssembler,
            json = json,
        )
    }
    override val connectionController: FoxholeConnectionController by lazy {
        FoxholeConnectionController(
            context = appContext,
            profileRepository = profileRepository,
            settingsRepository = settingsRepository,
            routingRepository = routingRepository,
            ipInfoRepository = ipInfoRepository,
            diagnosticsLogger = diagnosticsLogger,
            runtimeConfigAssembler = runtimeConfigAssembler,
        )
    }
}
