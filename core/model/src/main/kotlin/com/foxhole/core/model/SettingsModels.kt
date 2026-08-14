package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Separate from themeMode: changes only default panel surfaces, never the colour palette.
    val panelAppearance: PanelAppearance = PanelAppearance.STANDARD,
    val locale: AppLocale = AppLocale.SYSTEM,
    // False on a fresh install so the first-run wizard shows; stored payloads carry true.
    val onboardingCompleted: Boolean = false,
    // The beta notice follows quick start once, and only for builds that still call themselves beta.
    val betaNoticeAcknowledged: Boolean = false,
    // The quick start follows onboarding and precedes the beta notice. False by default on purpose:
    // the gate reads `onboardingCompleted && !quickStartShown`, which is false on the
    // pre-hydration defaults, so the sheet can never flash before the stored payload is read.
    val quickStartShown: Boolean = false,
    val showExpertSettings: Boolean = false,
    val supportBotHandleOverride: String? = null,
    val networkCardEnabled: Boolean = true,
    // Off hides the widget's DNS rows; rides the fast UI cache for cold-start geometry.
    val networkDnsInfoEnabled: Boolean = true,
    val trafficCardEnabled: Boolean = true,
    // RETIRED (dev.38): the card can no longer be hidden; kept so stored payloads keep decoding.
    val profileCardEnabled: Boolean = true,
    // Enabling prompts to download the geo DB first; countries resolve locally, never per-IP online.
    val trafficMapEnabled: Boolean = false,
    val showFirewallStatus: Boolean = false,
    // LAN proxy pill visibility is owned by this flag, not by service state (survives proxy off).
    val showLanProxyQuickAccess: Boolean = false,
    // Tor pill; both switch and pill are gated on PrivacyRouteSettings.permitted.
    val showTorQuickLaunch: Boolean = true,
    // I2P quick-access pill on the dashboard, the Tor pill's mirror. An ENABLED i2p keeps the
    // pill visible regardless, so a running node never becomes uncontrollable.
    val showI2pQuickLaunch: Boolean = true,
    // Off: only VPN buttons; Tor is then controlled from the quick-access Tor window.
    val showTorDashboardControls: Boolean = true,
    // RETIRED: kept so older payloads decode. FoxCore always owns a protected VpnService/TUN;
    // local SOCKS/HTTP listeners are surfaces on that tunnel, never a proxy-only runtime.
    val showTrafficModeSelector: Boolean = false,
    // Quick-access block in the status card; rides the fast UI cache for cold-start layout.
    val showQuickAccessPanel: Boolean = true,
    // Collapsing only hides the rows — every value inside keeps its stored state.
    val interfaceSettingsExpanded: Boolean = false,
    val layoutEditingEnabled: Boolean = true,
    // When each privacy core was last switched ON (epoch ms; 0 = off or enabled before these
    // stamps existed). The TOR/I2P settings entry and screen order themselves by whichever core
    // was enabled first; a stamp clears to 0 on disable so a re-enable counts as the newer one.
    val torEnabledAtMs: Long = 0L,
    val i2pEnabledAtMs: Long = 0L,
    // Build whose alpha notice was acknowledged; 0 = never shown.
    val alphaNoticeShownVersionCode: Int = 0,
    val dashboardCardOrder: List<DashboardCard> = DashboardCard.entries,
    // Drag-to-reorder profile order; missing ids keep repository order, appended at the end.
    val profileListOrder: List<Long> = emptyList(),
    val statisticsWidgetOrder: List<StatisticsWidgetId> = StatisticsWidgetId.entries,
    val trafficMapSectionOrder: List<TrafficMapSectionId> = TrafficMapSectionId.entries,
    val suppressProfileSwipeReconnectConfirm: Boolean = false,
    val suppressTrafficClearConfirm: Boolean = false,
    // Covers every firewall-enable warning surface (consent, dashboard window, indirect prompts).
    val suppressFirewallEnableWarning: Boolean = false,
    // Off renders the frosted chrome as solid tints for weak GPUs.
    val blurEffectsEnabled: Boolean = true,
    // App-wide: TOR/I2P wear the darkened theme accent instead of their fixed hues everywhere
    // (map legs, status tones, TOR window, traffic-chart series).
    val monochromeTorTheme: Boolean = false,
    // Mirrors the map-widget body (map right, legend left); the card header never mirrors.
    val mapWidgetMapOnRight: Boolean = false,
    // "Clear map history" watermark: the map ignores events before this moment; the underlying
    // journals/statistics stay intact.
    val trafficMapHistoryClearedAtMs: Long = 0L,
    // TEXT vs CHART body. Rides the fast UI cache — the bodies differ in height, so cold start
    // must lay the card out right.
    val trafficCardView: TrafficCardView = TrafficCardView.TEXT,
    // RETIRED: kept for decoding settings written before the combined-chart switch was introduced.
    val trafficChartSplit: Boolean = true,
    // CHART only: one combined graph; off follows the remembered network page.
    val trafficChartCombined: Boolean = true,
    // Last detailed VPN/TOR/I2P page shared by the Network and Traffic widgets.
    val trafficChartPage: TrafficChartPage = TrafficChartPage.VPN,
    // CHART only: the live window length in minutes (5 / 10 / custom, clamped 1..240).
    val trafficChartRangeMinutes: Int = 5,
    // Carries a default so old payloads decode and SETTINGS_SCHEMA_VERSION stays put.
    val statisticsWindow: StatisticsWindow = StatisticsWindow.DAY,
)

const val TRAFFIC_CHART_RANGE_MIN_MINUTES = 1

const val TRAFFIC_CHART_RANGE_MAX_MINUTES = 240

@Serializable
@Immutable
data class ConnectionSettings(
    // Reconnect after the tunnel drops on its own (health loss, network flap). Nothing else:
    // it never starts a connection the user did not ask for.
    val autoReconnect: Boolean = true,
    // Seamless routing switching. On (the default) a routing-mode change is applied to the live
    // runtime in place; off makes every routing-mode change ask first. Per-app and per-domain
    // rules ignore this flag entirely — those are always applied atomically.
    // Carries a default so old payloads decode and SETTINGS_SCHEMA_VERSION stays put.
    val atomicConnection: Boolean = true,
    val autoStartOnBoot: Boolean = false,
    // On by default: stale v2raytun subscription profiles are the #1 "my VPN stopped working".
    val autoRefreshSubscriptions: Boolean = true,
    val subscriptionRefreshInterval: SubscriptionRefreshInterval = SubscriptionRefreshInterval.HOURS_6,
    val ipInfoEndpoint: String = "",
    val latencyProbeMethod: LatencyProbeMethod = LatencyProbeMethod.HTTP,
    @SerialName("stealthModeEnabled")
    val safeModeEnabled: Boolean = true,
    // Default off: the user opts into scheduled fetches from the public GitHub mirror.
    val geoIpAutoUpdate: Boolean = false,
    // Check gates every background version probe (and the settings-home "updates" dot); auto
    // enables the scheduled 12h asset downloads. Auto is inert while check is off.
    val componentUpdateCheckEnabled: Boolean = true,
    val componentAutoUpdateEnabled: Boolean = false,
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
    // Without this opt-in a network change only yields a recommendation requiring confirmation.
    val wifiAutoConnect: Boolean = true,
    val cellularAutoConnect: Boolean = true,
)

@Serializable
@Immutable
data class PrivacyRouteSettings(
    val mode: PrivacyRouteMode = PrivacyRouteMode.OFF,
    // Selected apps live in [ExpertSettings.appAssignments] as the TOR lane.
    val scope: PrivacyRouteScope = PrivacyRouteScope.ALL_APPS,
    val bypassVpnTunnel: Boolean = false,
    val udpPolicy: PrivacyRouteUdpPolicy = PrivacyRouteUdpPolicy.VPN,
    val identityVersion: Long = 0L,
    // Fail-closed Tor lane: pinned apps lose the network while Tor is disengaged instead of
    // falling through to the plain VPN/direct path.
    val blockAppsWhenTorUnavailable: Boolean = false,
    // While CONNECTED the rotation supervisor requests a fresh circuit every interval.
    val autoRotateExit: Boolean = false,
    val autoRotateIntervalMinutes: Int = 15,
    // Permission gate for the Tor core; never starts anything by itself. Revoking must fully
    // stop an engaged Tor and hides/disables every Tor control.
    val permitted: Boolean = false,
    // Bridges only apply when Tor dials out from the device itself (Tor-only / bypass-VPN); the
    // Tor-over-VPN detour drops them — pluggable transports do not survive the in-tunnel detour.
    val bridgesEnabled: Boolean = true,
    val bridgeTransport: TorBridgeTransport = TorBridgeTransport.AUTO,
    // Scheduled bridge-list refresh (12h WorkManager job); off = the bundled/last-downloaded list.
    val bridgesAutoUpdate: Boolean = false,
    // false = Tor Project builtin-bridges endpoint, true = the Foxhole GitHub mirror.
    val bridgesUseFoxholeSource: Boolean = false,
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
    val newAppQuarantineEnabled: Boolean = false,
    val systemDnsProtectionEnabled: Boolean = false,
    // Connections journal, raw by default; persistence stays firewall-gated.
    val networkActivityLogging: Boolean = true,
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
    // Immutable allow-list captured when native new-app quarantine is armed. It deliberately does
    // not follow PackageManager on every runtime start: packages installed while the runtime is
    // down must remain unknown (and therefore blocked) until the user makes a decision.
    val quarantineKnownApplications: List<KnownApplicationIdentity> = emptyList(),
    // Every pending entry is also assigned the BLOCK lane, so a process restart cannot create an
    // unprotected gap.
    val pendingQuarantinePackages: List<String> = emptyList(),
    // Display facts for the pending decision. The package list above remains the source of truth;
    // this device-local companion is pruned to that list during normalization and backup export.
    val pendingQuarantineAppDetails: List<PendingQuarantineAppDetails> = emptyList(),
    // Monotonic durable revision of the native quarantine/BLOCK policy. Runtime enforcement acks
    // this exact value only after the session assembled from it reaches the native data plane.
    val quarantinePolicyRevision: Long = 0L,
    val blockedPackagesEnabled: Boolean = false,
    val blockAppsAlways: Boolean = false,
    val siteRoutingAction: RoutingRuleAction = RoutingRuleAction.PROXY,
    val localSurfaces: LocalSurfaceSettings = LocalSurfaceSettings(),
)

@Serializable
@Immutable
data class StatisticsSettings(
    // Collection master — every collector reads this.
    val enabled: Boolean = false,
    // Only reveals/hides the statistics screen; collection is opted into inside that screen.
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
    // Live screens keep updating, but every store is wiped at session end and on cold start.
    val sessionOnly: Boolean = false,
)

/**
 * Where updates are fetched from. Every field blank means the built-in FoxHole sources, which is
 * what a normal install runs on; a value here redirects one feed and nothing else.
 *
 * Redirecting the data repository does NOT relax any check: manifests are still verified against
 * the pinned FoxHole DB key, so only a mirror published by the same tooling can serve them. That is
 * the point of the setting — it moves the host, not the trust.
 *
 * [appReleasesToken] exists for one case: an app repository that is private, where the releases API
 * answers 404 to an anonymous reader. It is sent to the configured host only, never to the default
 * one, and it lives in the encrypted settings store like every other credential here.
 */
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
    // Carries a default so old payloads decode and SETTINGS_SCHEMA_VERSION stays put.
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
