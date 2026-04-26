package com.foxhole.beta

import android.content.Context
import com.foxhole.beta.core.data.ProfileDatabase
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.network.NetworkFingerprintProvider
import com.foxhole.beta.core.settings.SettingsRepository
import com.foxhole.beta.vpn.FoxholeConnectionController
import com.foxhole.beta.vpn.RuntimeConfigAssembler

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
    val settingsRepository: SettingsRepository
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
    private val coreModule = FoxholeCoreGraphModule(appContext)
    private val dataModule = FoxholeDataGraphModule(appContext, coreModule)
    private val runtimeModule = FoxholeRuntimeGraphModule(appContext, coreModule, dataModule)

    override val settingsRepository: SettingsRepository by lazy { coreModule.settingsRepository }
    override val diagnosticsLogger: DiagnosticsLogger by lazy { coreModule.diagnosticsLogger }
    val profileDatabase: ProfileDatabase by lazy { dataModule.profileDatabase }
    override val routingRepository: RoutingRepository by lazy { dataModule.routingRepository }
    override val runtimeConfigAssembler: RuntimeConfigAssembler by lazy { dataModule.runtimeConfigAssembler }
    override val ipInfoRepository: IpInfoRepository by lazy { runtimeModule.ipInfoRepository }
    override val networkFingerprintProvider: NetworkFingerprintProvider by lazy { runtimeModule.networkFingerprintProvider }
    override val profileRepository: ProfileRepository by lazy { dataModule.profileRepository }
    override val connectionController: FoxholeConnectionController by lazy { runtimeModule.connectionController }
}
