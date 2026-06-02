package com.foxhole.beta

import android.content.Context
import com.foxhole.beta.core.anomaly.AnomalyRepository
import com.foxhole.beta.core.data.ProfileDatabase
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.network.NetworkFingerprintProvider
import com.foxhole.beta.core.settings.SettingsRepository
import com.foxhole.beta.core.traffic.TrafficMapRepository
import com.foxhole.beta.vpn.DnsFilterAssetInstaller
import com.foxhole.beta.vpn.DnsFilterUpdateRepository
import com.foxhole.beta.vpn.FoxholeConnectionController
import com.foxhole.beta.vpn.RuntimeConfigAssembler
import com.foxhole.beta.vpn.TorManager

interface FoxholeStartupDependencies {
    val settingsRepository: SettingsRepository
    val profileRepository: ProfileRepository
    val diagnosticsLogger: DiagnosticsLogger
}

interface FoxholeHomeDependencies {
    val settingsRepository: SettingsRepository
    val profileRepository: ProfileRepository
    val dnsFilterAssetInstaller: DnsFilterAssetInstaller
    val dnsFilterUpdateRepository: DnsFilterUpdateRepository
    val routingRepository: RoutingRepository
    val connectionController: FoxholeConnectionController
    val diagnosticsLogger: DiagnosticsLogger
    val networkFingerprintProvider: NetworkFingerprintProvider
    val runtimeConfigAssembler: RuntimeConfigAssembler
    val trafficMapRepository: TrafficMapRepository
    val anomalyRepository: AnomalyRepository
}

interface FoxholeRuntimeDependencies {
    val settingsRepository: SettingsRepository
    val profileRepository: ProfileRepository
    val diagnosticsLogger: DiagnosticsLogger
    val networkFingerprintProvider: NetworkFingerprintProvider
    val connectionController: FoxholeConnectionController
    val ipInfoRepository: IpInfoRepository
    val runtimeConfigAssembler: RuntimeConfigAssembler
    val dnsFilterAssetInstaller: DnsFilterAssetInstaller
    val torManager: TorManager
    val trafficMapRepository: TrafficMapRepository
    val anomalyRepository: AnomalyRepository
}

interface FoxholeTileDependencies {
    val settingsRepository: SettingsRepository
    val diagnosticsLogger: DiagnosticsLogger
    val connectionController: FoxholeConnectionController
}

interface FoxholeRefreshWorkerDependencies {
    val settingsRepository: SettingsRepository
    val profileRepository: ProfileRepository
    val diagnosticsLogger: DiagnosticsLogger
}

interface FoxholeDnsFilterUpdateDependencies {
    val dnsFilterUpdateRepository: DnsFilterUpdateRepository
}

interface FoxholeDiagnosticsDependencies {
    val diagnosticsLogger: DiagnosticsLogger
}

interface FoxholeProfileMaintenanceDependencies {
    val profileRepository: ProfileRepository
    val diagnosticsLogger: DiagnosticsLogger
}

class FoxholeAppGraph(
    context: Context,
) : FoxholeStartupDependencies,
    FoxholeHomeDependencies,
    FoxholeRuntimeDependencies,
    FoxholeTileDependencies,
    FoxholeRefreshWorkerDependencies,
    FoxholeDnsFilterUpdateDependencies,
    FoxholeDiagnosticsDependencies,
    FoxholeProfileMaintenanceDependencies {
    private val appContext = context.applicationContext
    private val coreModule = FoxholeCoreGraphModule(appContext)
    private val dataModule = FoxholeDataGraphModule(appContext, coreModule)
    private val runtimeModule = FoxholeRuntimeGraphModule(appContext, coreModule, dataModule)

    override val settingsRepository: SettingsRepository by lazy { coreModule.settingsRepository }
    override val diagnosticsLogger: DiagnosticsLogger by lazy { coreModule.diagnosticsLogger }
    val profileDatabase: ProfileDatabase by lazy { dataModule.profileDatabase }
    override val routingRepository: RoutingRepository by lazy { dataModule.routingRepository }
    override val runtimeConfigAssembler: RuntimeConfigAssembler by lazy { dataModule.runtimeConfigAssembler }
    override val trafficMapRepository: TrafficMapRepository by lazy { coreModule.trafficMapRepository }
    override val anomalyRepository: AnomalyRepository by lazy { dataModule.anomalyRepository }
    override val ipInfoRepository: IpInfoRepository by lazy { runtimeModule.ipInfoRepository }
    override val networkFingerprintProvider: NetworkFingerprintProvider by lazy { runtimeModule.networkFingerprintProvider }
    override val profileRepository: ProfileRepository by lazy { dataModule.profileRepository }
    override val dnsFilterAssetInstaller: DnsFilterAssetInstaller by lazy { dataModule.dnsFilterAssetInstaller }
    override val dnsFilterUpdateRepository: DnsFilterUpdateRepository by lazy { dataModule.dnsFilterUpdateRepository }
    override val torManager: TorManager by lazy { dataModule.torManager }
    override val connectionController: FoxholeConnectionController by lazy { runtimeModule.connectionController }
}
