package com.foxhole.guard

import android.content.Context
import com.foxhole.core.runtime.I2pdManager
import com.foxhole.core.runtime.LanProxyAddressProvider
import com.foxhole.core.runtime.RuntimeConfigAssembler
import com.foxhole.core.runtime.RuntimeDnsRuleSetInstallOutcome
import com.foxhole.core.runtime.RuntimeInstanceStore
import com.foxhole.core.runtime.RuntimeSupervisor
import com.foxhole.core.runtime.network.IpInfoRepository
import com.foxhole.core.runtime.network.NetworkFingerprintProvider
import com.foxhole.guard.core.data.I2pTrafficRepository
import com.foxhole.guard.core.data.LocalDataRepository
import com.foxhole.guard.core.data.ProfileDatabase
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.core.data.RoutingRepository
import com.foxhole.guard.core.data.WebAppsRepository
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.security.AppLockManager
import com.foxhole.guard.core.security.FoxholeSecurityComponents
import com.foxhole.guard.core.security.SecureSessionHolder
import com.foxhole.guard.core.sentinel.anomaly.AnomalyRepository
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.webapps.WebAppsDataCleaner
import com.foxhole.guard.core.webapps.WebAppsNotifier
import com.foxhole.guard.core.webapps.WebAppsWatchdog
import com.foxhole.guard.runtime.AppUpdateRepository
import com.foxhole.guard.runtime.DnsFilterAssetInstaller
import com.foxhole.guard.runtime.DnsFilterUpdateRepository
import com.foxhole.guard.runtime.FoxholeConnectionController
import com.foxhole.guard.runtime.GeoIpUpdateRepository
import com.foxhole.guard.runtime.ThreatIntelUpdateRepository
import com.foxhole.guard.runtime.TlsFingerprintProvider
import com.foxhole.guard.runtime.TlsFingerprintUpdateRepository
import com.foxhole.guard.runtime.TorBridgeUpdateRepository
import com.foxhole.guard.traffic.TrafficMapRepository

interface FoxholeStartupDependencies {
    val settingsRepository: SettingsRepository
    val profileRepository: ProfileRepository
    val diagnosticsLogger: DiagnosticsLogger
}

interface FoxholeHomeDependencies {
    val settingsRepository: SettingsRepository
    val profileRepository: ProfileRepository
    val dnsFilterAssetInstaller: DnsFilterAssetInstaller
    val appUpdateRepository: AppUpdateRepository
    val dnsFilterUpdateRepository: DnsFilterUpdateRepository
    val geoIpUpdateRepository: GeoIpUpdateRepository
    val torBridgeUpdateRepository: TorBridgeUpdateRepository
    val threatIntelUpdateRepository: ThreatIntelUpdateRepository
    val tlsFingerprintUpdateRepository: TlsFingerprintUpdateRepository
    val routingRepository: RoutingRepository
    val connectionController: FoxholeConnectionController
    val diagnosticsLogger: DiagnosticsLogger
    val networkFingerprintProvider: NetworkFingerprintProvider
    val runtimeConfigAssembler: RuntimeConfigAssembler
    val trafficMapRepository: TrafficMapRepository
    val anomalyRepository: AnomalyRepository
    val i2pTrafficRepository: I2pTrafficRepository
    val localDataRepository: LocalDataRepository
    val ipInfoRepository: IpInfoRepository
    val webAppsRepository: WebAppsRepository
    val runtimeInstanceStore: RuntimeInstanceStore
}

interface FoxholeRuntimeDependencies {
    val settingsRepository: SettingsRepository
    val profileRepository: ProfileRepository
    val routingRepository: RoutingRepository
    val diagnosticsLogger: DiagnosticsLogger
    val networkFingerprintProvider: NetworkFingerprintProvider
    val connectionController: FoxholeConnectionController
    val ipInfoRepository: IpInfoRepository
    val runtimeConfigAssembler: RuntimeConfigAssembler

    /** Shared with the config assembler so the arming decision and the bind use the same network. */
    val lanProxyAddressProvider: LanProxyAddressProvider
    val dnsFilterAssetInstaller: DnsFilterAssetInstaller
    val i2pdManager: I2pdManager
    val trafficMapRepository: TrafficMapRepository
    val anomalyRepository: AnomalyRepository
    val i2pTrafficRepository: I2pTrafficRepository
    val runtimeInstanceStore: RuntimeInstanceStore
    val runtimeSupervisor: RuntimeSupervisor
    val securityComponents: FoxholeSecurityComponents
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

interface FoxholeAppUpdateDependencies {
    val settingsRepository: SettingsRepository
    val appUpdateRepository: AppUpdateRepository
}

interface FoxholeGeoIpUpdateDependencies {
    val settingsRepository: SettingsRepository
    val geoIpUpdateRepository: GeoIpUpdateRepository
}

interface FoxholeTorBridgeUpdateDependencies {
    val settingsRepository: SettingsRepository
    val torBridgeUpdateRepository: TorBridgeUpdateRepository
}

interface FoxholeThreatIntelUpdateDependencies {
    val settingsRepository: SettingsRepository
    val threatIntelUpdateRepository: ThreatIntelUpdateRepository
}

interface FoxholeTlsFingerprintUpdateDependencies {
    val settingsRepository: SettingsRepository
    val tlsFingerprintUpdateRepository: TlsFingerprintUpdateRepository
}

interface FoxholeDiagnosticsDependencies {
    val diagnosticsLogger: DiagnosticsLogger
}

interface FoxholeProfileMaintenanceDependencies {
    val profileRepository: ProfileRepository
    val diagnosticsLogger: DiagnosticsLogger
}

interface FoxholeSecurityDependencies {
    val appLockManager: AppLockManager
    val secureSessionHolder: SecureSessionHolder
    val securityComponents: FoxholeSecurityComponents
}

class FoxholeAppGraph(
    context: Context,
) : FoxholeStartupDependencies,
    FoxholeHomeDependencies,
    FoxholeRuntimeDependencies,
    FoxholeTileDependencies,
    FoxholeRefreshWorkerDependencies,
    FoxholeDnsFilterUpdateDependencies,
    FoxholeAppUpdateDependencies,
    FoxholeGeoIpUpdateDependencies,
    FoxholeTorBridgeUpdateDependencies,
    FoxholeThreatIntelUpdateDependencies,
    FoxholeTlsFingerprintUpdateDependencies,
    FoxholeDiagnosticsDependencies,
    FoxholeProfileMaintenanceDependencies,
    FoxholeSecurityDependencies {
    private val appContext = context.applicationContext
    private val coreModule = FoxholeCoreGraphModule(appContext)
    override val securityComponents: FoxholeSecurityComponents by lazy {
        FoxholeSecurityComponents(
            appContext,
            coreModule.settingsRepository,
            recordSecurityDiagnostic = { message -> coreModule.diagnosticsLogger.record("security", message) },
        )
    }
    private val dataModule: FoxholeDataGraphModule =
        FoxholeDataGraphModule(
            appContext = appContext,
            core = coreModule,
            security = { securityComponents },
            liveDnsRuleSetInstaller = { name, manifest, signature, artifact ->
                runtimeModule.runtimeInstanceStore
                    .current()
                    ?.installDnsRuleSet(name, manifest, signature, artifact)
                    ?: RuntimeDnsRuleSetInstallOutcome.Deferred
            },
        )
    private val runtimeModule: FoxholeRuntimeGraphModule =
        FoxholeRuntimeGraphModule(appContext, coreModule, dataModule)

    override val settingsRepository: SettingsRepository by lazy { coreModule.settingsRepository }
    override val diagnosticsLogger: DiagnosticsLogger by lazy { coreModule.diagnosticsLogger }
    override val appLockManager: AppLockManager by lazy { securityComponents.appLockManager }
    override val secureSessionHolder: SecureSessionHolder by lazy { securityComponents.secureSessionHolder }
    val profileDatabase: ProfileDatabase by lazy { dataModule.profileDatabase }
    internal val webAppsWatchdog: WebAppsWatchdog by lazy { runtimeModule.webAppsWatchdog }
    internal val webAppsNotifier: WebAppsNotifier by lazy { coreModule.webAppsNotifier }
    internal val webAppsDataCleaner: WebAppsDataCleaner by lazy { coreModule.webAppsDataCleaner }

    // Offline IP -> ISO country lookup (geo database); call off the main thread.
    val ipCountryCodeResolver: (String) -> String? get() = runtimeModule.ipCountryCodeResolver
    override val routingRepository: RoutingRepository by lazy { dataModule.routingRepository }
    override val runtimeConfigAssembler: RuntimeConfigAssembler by lazy { dataModule.runtimeConfigAssembler }
    override val lanProxyAddressProvider: LanProxyAddressProvider by lazy { dataModule.lanProxyAddressProvider }
    override val trafficMapRepository: TrafficMapRepository by lazy { coreModule.trafficMapRepository }
    override val anomalyRepository: AnomalyRepository by lazy { dataModule.anomalyRepository }
    override val i2pTrafficRepository: I2pTrafficRepository by lazy { dataModule.i2pTrafficRepository }
    override val webAppsRepository: WebAppsRepository by lazy { dataModule.webAppsRepository }
    override val localDataRepository: LocalDataRepository by lazy { dataModule.localDataRepository }
    override val ipInfoRepository: IpInfoRepository by lazy { runtimeModule.ipInfoRepository }
    override val networkFingerprintProvider: NetworkFingerprintProvider by lazy { runtimeModule.networkFingerprintProvider }
    override val profileRepository: ProfileRepository by lazy { dataModule.profileRepository }
    override val dnsFilterAssetInstaller: DnsFilterAssetInstaller by lazy { dataModule.dnsFilterAssetInstaller }
    override val appUpdateRepository: AppUpdateRepository by lazy { dataModule.appUpdateRepository }
    override val dnsFilterUpdateRepository: DnsFilterUpdateRepository by lazy { dataModule.dnsFilterUpdateRepository }
    override val geoIpUpdateRepository: GeoIpUpdateRepository by lazy { dataModule.geoIpUpdateRepository }
    override val torBridgeUpdateRepository: TorBridgeUpdateRepository by lazy { dataModule.torBridgeUpdateRepository }
    override val threatIntelUpdateRepository: ThreatIntelUpdateRepository by lazy { dataModule.threatIntelUpdateRepository }
    override val tlsFingerprintUpdateRepository: TlsFingerprintUpdateRepository by lazy {
        dataModule.tlsFingerprintUpdateRepository
    }
    val tlsFingerprintProvider: TlsFingerprintProvider by lazy { dataModule.tlsFingerprintProvider }
    override val i2pdManager: I2pdManager by lazy { dataModule.i2pdManager }
    override val runtimeInstanceStore: RuntimeInstanceStore by lazy { runtimeModule.runtimeInstanceStore }
    override val runtimeSupervisor: RuntimeSupervisor by lazy { runtimeModule.runtimeSupervisor }
    override val connectionController: FoxholeConnectionController by lazy { runtimeModule.connectionController }
}
