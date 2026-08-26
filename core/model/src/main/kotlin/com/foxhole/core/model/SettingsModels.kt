package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    // Legacy storage only. ThemeMode is the single system/dark/OLED/light source.
    val panelAppearance: PanelAppearance = PanelAppearance.AUTO,
    val accentColor: AccentColor = AccentColor.ORANGE,
    val locale: AppLocale = AppLocale.SYSTEM,

    val pixelArtEnabled: Boolean = true,

    val onboardingCompleted: Boolean = false,

    val betaNoticeAcknowledged: Boolean = false,

    val quickStartShown: Boolean = false,
    val showExpertSettings: Boolean = false,
    val supportBotHandleOverride: String? = null,
    val networkCardEnabled: Boolean = true,

    val networkDnsInfoEnabled: Boolean = true,
    val trafficCardEnabled: Boolean = true,

    val profileCardEnabled: Boolean = true,
    // Enabling prompts to download the geo DB first; countries resolve locally, never per-IP online.
    val trafficMapEnabled: Boolean = false,
    val showFirewallStatus: Boolean = false,

    val showLanProxyQuickAccess: Boolean = false,

    val showTorQuickLaunch: Boolean = true,

    val showI2pQuickLaunch: Boolean = true,

    val showTorDashboardControls: Boolean = true,

    val showTrafficModeSelector: Boolean = false,

    val showQuickAccessPanel: Boolean = true,

    val interfaceSettingsExpanded: Boolean = false,
    val layoutEditingEnabled: Boolean = true,

    val torEnabledAtMs: Long = 0L,
    val i2pEnabledAtMs: Long = 0L,
    // Build whose alpha notice was acknowledged; 0 = never shown.
    val alphaNoticeShownVersionCode: Int = 0,
    val dashboardCardOrder: List<DashboardCard> = DashboardCard.entries,

    val profileListOrder: List<Long> = emptyList(),
    val statisticsWidgetOrder: List<StatisticsWidgetId> = StatisticsWidgetId.entries,
    val trafficMapSectionOrder: List<TrafficMapSectionId> = TrafficMapSectionId.entries,
    val showHomeAdditionalInfo: Boolean = false,
    val homeAdditionalInfoCategory: HomeAdditionalInfoCategory = HomeAdditionalInfoCategory.MAP,
    val suppressProfileSwipeReconnectConfirm: Boolean = false,
    val suppressTrafficClearConfirm: Boolean = false,

    val suppressFirewallEnableWarning: Boolean = false,

    val blurEffectsEnabled: Boolean = false,

    val monochromeTorTheme: Boolean = false,
    // Mirrors the map-widget body (map right, legend left); the card header never mirrors.
    val mapWidgetMapOnRight: Boolean = false,

    val trafficMapHistoryClearedAtMs: Long = 0L,

    val trafficCardView: TrafficCardView = TrafficCardView.TEXT,

    val trafficChartSplit: Boolean = true,

    val trafficChartCombined: Boolean = true,

    val trafficChartPage: TrafficChartPage = TrafficChartPage.VPN,

    val trafficChartRangeMinutes: Int = 5,

    val statisticsWindow: StatisticsWindow = StatisticsWindow.DAY,

    val statisticsDockIconEnabled: Boolean = false,
)

@Immutable
data class AppearanceUiState(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentColor: AccentColor = AccentColor.ORANGE,
    val pixelArtEnabled: Boolean = true,
)

fun UiSettings.appearanceUiState(): AppearanceUiState =
    AppearanceUiState(
        themeMode = themeMode,
        accentColor = accentColor,
        pixelArtEnabled = pixelArtEnabled,
    )

fun UiSettings.withAppearanceUiState(value: AppearanceUiState): UiSettings =
    copy(
        themeMode = value.themeMode,
        panelAppearance = PanelAppearance.AUTO,
        accentColor = value.accentColor,
        pixelArtEnabled = value.pixelArtEnabled,
    )

const val TRAFFIC_CHART_RANGE_MIN_MINUTES = 1

const val TRAFFIC_CHART_RANGE_MAX_MINUTES = 240

@Serializable
@Immutable
data class ConnectionSettings(

    val autoReconnect: Boolean = true,

    val atomicConnection: Boolean = true,
    val autoStartOnBoot: Boolean = false,

    val autoRefreshSubscriptions: Boolean = true,
    val subscriptionRefreshInterval: SubscriptionRefreshInterval = SubscriptionRefreshInterval.HOURS_6,
    val ipInfoEndpoint: String = "",
    val latencyProbeMethod: LatencyProbeMethod = LatencyProbeMethod.HTTP,
    @SerialName("stealthModeEnabled")
    val safeModeEnabled: Boolean = true,

    val geoIpAutoUpdate: Boolean = false,

    val componentUpdateCheckEnabled: Boolean = true,
    val componentAutoUpdateEnabled: Boolean = false,
    val tlsFingerprintAutoUpdate: Boolean = true,
    // Resolve only IP + country from the on-device GeoIP DB, never the online IP-info service.
    val geoOfflineMode: Boolean = false,
)

@Serializable
@Immutable
data class CachedActiveProfile(
    val id: Long,
    val name: String,
    val sourceType: ProfileSourceType,
    val protocolHint: ProtocolHint,
)

@Serializable
@Immutable
data class TrafficSettings(
    val mode: TrafficMode = TrafficMode.TUNNEL,
    val tunStack: TunStack = TunStack.SYSTEM,
    val mtu: Int = 1500,
    val preferIpv6: Boolean = false,
    val domainStrategy: DomainStrategy = DomainStrategy.PREFER_IPV4,
)

@Serializable
@Immutable
data class NetworkRulesSettings(
    val wifiRulesEnabled: Boolean = false,
    val cellularRulesEnabled: Boolean = false,
    val skipSubscriptionRefreshOnCellular: Boolean = true,
    val skipSpeedTestsOnCellular: Boolean = true,
    val useWifiProfile: Boolean = false,
    val wifiProfileId: Long? = null,
    val wifiProtocolOptionId: String? = null,
    val useCellularProfile: Boolean = false,
    val cellularProfileId: Long? = null,
    val cellularProtocolOptionId: String? = null,

    val wifiAutoConnect: Boolean = true,
    val cellularAutoConnect: Boolean = true,
)

@Serializable
@Immutable
data class PrivacyRouteSettings(
    val mode: PrivacyRouteMode = PrivacyRouteMode.OFF,

    val scope: PrivacyRouteScope = PrivacyRouteScope.ALL_APPS,
    val bypassVpnTunnel: Boolean = false,
    val udpPolicy: PrivacyRouteUdpPolicy = PrivacyRouteUdpPolicy.VPN,
    val identityVersion: Long = 0L,

    val blockAppsWhenTorUnavailable: Boolean = false,

    val autoRotateExit: Boolean = false,
    val autoRotateIntervalMinutes: Int = 15,

    val permitted: Boolean = false,

    val bridgesEnabled: Boolean = true,
    val bridgeTransport: TorBridgeTransport = TorBridgeTransport.AUTO,
    // Scheduled bridge-list refresh (12h WorkManager job); off = the bundled/last-downloaded list.
    val bridgesAutoUpdate: Boolean = false,
    val bridgesUseFoxholeSource: Boolean = true,
    val bridgesUpdatedAt: Long? = null,
    val bridgesCheckedAt: Long? = null,
    val bridgesLastUpdateSuccess: Boolean? = null,
) {
    val enabled: Boolean
        get() = mode == PrivacyRouteMode.TOR_OVER_VPN

    val directTorEnabled: Boolean
        get() = enabled && bypassVpnTunnel
}

const val I2P_TRANSIT_TUNNELS_MIN = 2

const val I2P_TRANSIT_TUNNELS_MAX = 25_000

@Serializable
@Immutable
data class ProxyInboundSettings(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 10808,
)

@Serializable
@Immutable
data class LocalAuthSettings(
    val enabled: Boolean = true,
    val username: String = "",
    val password: String = "",
    val apiSecret: String = "",
)

@Serializable
@Immutable
data class ClashApiSettings(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 9090,
    val secret: String = "",
)

@Serializable
@Immutable
data class V2RayApiSettings(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 10085,
)

@Serializable
@Immutable
data class LocalSurfaceSettings(
    val proxyMode: ProxySurfaceMode = ProxySurfaceMode.SOCKS5,
    val lanProxyMode: ProxySurfaceMode = ProxySurfaceMode.SOCKS5,
    val socks: ProxyInboundSettings = ProxyInboundSettings(port = 10808),
    val http: ProxyInboundSettings = ProxyInboundSettings(port = 10809),
    val mixed: ProxyInboundSettings = ProxyInboundSettings(port = 10810),
    val allowLanAccess: Boolean = false,
    val clashApi: ClashApiSettings = ClashApiSettings(),
    val v2RayApi: V2RayApiSettings = V2RayApiSettings(),
    val auth: LocalAuthSettings = LocalAuthSettings(),
    val lanAuth: LocalAuthSettings = LocalAuthSettings(),
)

@Serializable
@Immutable
data class ExpertSettings(
    val unlockedAt: Long? = null,
    val warningAcknowledgedAt: Long? = null,
    val blockScreenshots: Boolean = false,
    val firewallEnabled: Boolean = false,
    val killSwitchEnabled: Boolean = false,
    val newAppQuarantineEnabled: Boolean = false,
    val systemDnsProtectionEnabled: Boolean = false,
    val networkActivityLogging: Boolean = false,
    // Legacy fallback behind [effectiveDiagnosticsRetention]. Do not read directly.
    val diagnosticsRetention: DiagnosticsRetention = DiagnosticsRetention.HOURS_24,
    val diagnosticsRetentionPolicy: RetentionPolicy? = null,
    val rawLiveDiagnostics: Boolean = false,
    val allowInsecureTls: Boolean = false,
    val sniff: Boolean = true,
    val strictRoute: Boolean = true,
    val bypassLan: Boolean = false,
    val allowPrivateOutboundHosts: Boolean = false,
    val perAppRoutingMode: PerAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
    // The single per-app routing decision; legacy selected/blocked lists migrate in at load time.
    val appAssignments: Map<String, AppTunnelLane> = emptyMap(),

    val quarantineKnownApplications: List<KnownApplicationIdentity> = emptyList(),

    val pendingQuarantinePackages: List<String> = emptyList(),

    val pendingQuarantineAppDetails: List<PendingQuarantineAppDetails> = emptyList(),

    val quarantinePolicyRevision: Long = 0L,
    val blockedPackagesEnabled: Boolean = false,
    val blockAppsAlways: Boolean = false,
    val siteRoutingAction: RoutingRuleAction = RoutingRuleAction.PROXY,
    val localSurfaces: LocalSurfaceSettings = LocalSurfaceSettings(),
)

@Serializable
@Immutable
data class StatisticsSettings(

    val enabled: Boolean = false,

    val componentVisible: Boolean = true,
    // Legacy fallback behind [effectiveRetention]. Do not read directly.
    val retention: StatisticsRetention = StatisticsRetention.WEEK,
    val retentionPolicy: RetentionPolicy? = null,
    val refreshInterval: StatisticsRefreshInterval = StatisticsRefreshInterval.SECONDS_3,
    val profileTrafficEnabled: Boolean = false,
    val appTrafficEnabled: Boolean = false,
    val dnsFilteringEnabled: Boolean = false,
    val countryTrafficEnabled: Boolean = false,
    val anomalyMetricsEnabled: Boolean = false,
    val appChangesEnabled: Boolean = false,

    val sessionOnly: Boolean = false,
)

@Serializable
@Immutable
data class UpdateSourceSettings(
    val databaseBaseUrl: String = "",
    val appReleasesUrl: String = "",
    val appReleasesToken: String = "",
)

@Serializable
@Immutable
data class Settings(
    val schemaVersion: Int = SETTINGS_SCHEMA_VERSION,
    val ui: UiSettings = UiSettings(),
    val connection: ConnectionSettings = ConnectionSettings(),

    val updateSources: UpdateSourceSettings = UpdateSourceSettings(),
    val traffic: TrafficSettings = TrafficSettings(),
    val dns: DnsSettings = DnsSettings(),
    val networkRules: NetworkRulesSettings = NetworkRulesSettings(),
    val privacyRoute: PrivacyRouteSettings = PrivacyRouteSettings(),
    val i2p: I2pSettings = I2pSettings(),
    val expert: ExpertSettings = ExpertSettings(),
    val statistics: StatisticsSettings = StatisticsSettings(),
    val anomaly: AnomalySettings = AnomalySettings(),
    val appLock: AppLockSettings = AppLockSettings(),
    val webApps: WebAppsSettings = WebAppsSettings(),
    val widgets: WidgetDefaultsSettings = WidgetDefaultsSettings(),
    val lastActiveProfile: CachedActiveProfile? = null,
    val smartProfilePreferences: List<SmartProfilePreference> = emptyList(),
    val profileTrafficTotals: List<ProfileTrafficTotal> = emptyList(),
    val installedAppInventoryAudit: InstalledAppInventoryAudit = InstalledAppInventoryAudit(),
    val appTrafficStatsEnabled: Boolean = false,
    val appTrafficUsageAccessConsent: Boolean = false,
    val usageTrackingStartedAt: Long = System.currentTimeMillis(),
)
