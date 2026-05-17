package com.foxhole.beta.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SETTINGS_SCHEMA_VERSION = 16
const val NETWORK_FINGERPRINT_SCHEMA_CURRENT = 2
const val NETWORK_FINGERPRINT_SCHEMA_LEGACY = 1

@Serializable
enum class ProfileSourceType {
    SUBSCRIPTION_URL,
    SHARE_URI,
    RAW_SINGBOX_JSON,
    RAW_WIREGUARD_TEXT,
}

@Serializable
enum class ProtocolHint {
    VLESS,
    TROJAN,
    SHADOWSOCKS,
    WIREGUARD,
    HYSTERIA2,
    VMESS,
    OUTLINE,
    SING_BOX,
    UNKNOWN,
}

@Serializable
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

@Serializable
enum class AppLocale(val tag: String) {
    SYSTEM(""),
    RU("ru"),
    EN("en"),

    ;

    fun appLanguageTags(): String = tag
}

@Serializable
enum class DashboardCard {
    TRAFFIC_MAP,
    PROFILES,
    ACTIONS,
    NETWORK,
    TRAFFIC,
}

@Serializable
enum class ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

@Serializable
enum class AutoConnectReasonCode(val wireCode: String) {
    HANDSHAKE_TIMEOUT("handshake_timeout"),
    VALIDATION_TIMEOUT("validation_timeout"),
    LATENCY_ENDPOINT_BLOCKED("latency_endpoint_blocked"),
    DNS_FAILURE("dns_failure"),
    CONNECT_ERROR("connect_error"),
    RESTORED_LAST_GOOD("restored_last_good"),
}

@Serializable
enum class LatencyProbeMethod {
    HTTP,
    ICMP,
    TCP,
}

@Serializable
enum class ConnectivityHealthState {
    CHECKING,
    ONLINE,
    OFFLINE,
}

@Serializable
enum class TunStack(val configValue: String) {
    SYSTEM("system"),
    GVISOR("gvisor"),
}

@Serializable
enum class TrafficMode {
    TUNNEL,
    PROXY,
}

@Serializable
enum class ProxySurfaceMode {
    SOCKS5,
    HTTP,
    ALL,
}

internal fun ProtocolHint.isUdpTransport(): Boolean =
    this == ProtocolHint.HYSTERIA2 || this == ProtocolHint.WIREGUARD

const val SMART_START_PROTOCOL_TIMEOUT_MIN_SECONDS = 5
const val SMART_START_PROTOCOL_TIMEOUT_DEFAULT_SECONDS = 10
const val SMART_START_REFRESH_TIMEOUT_MIN_SECONDS = 10
const val SMART_START_REFRESH_TIMEOUT_DEFAULT_SECONDS = 15
const val SMART_START_TIMEOUT_MAX_SECONDS = 60
const val SMART_START_TIMEOUT_STEP_SECONDS = 5
const val SMART_START_SUBSCRIPTION_RETRY_ATTEMPTS_DEFAULT = 3
const val SMART_START_SUBSCRIPTION_RETRY_DELAY_DEFAULT_SECONDS = 5

@Serializable
enum class SmartStartTransportPriority {
    ALL,
    UDP,
    TCP,
}

@Serializable
enum class SubscriptionRefreshInterval(val hours: Long) {
    HOURS_6(6),
    HOURS_12(12),
    HOURS_24(24),
}

@Serializable
enum class DomainStrategy(val configValue: String) {
    AS_IS("as_is"),
    PREFER_IPV4("prefer_ipv4"),
    PREFER_IPV6("prefer_ipv6"),
    IPV4_ONLY("ipv4_only"),
    IPV6_ONLY("ipv6_only"),
}

@Serializable
enum class SecureDnsMode(
    val configType: String,
    val defaultPort: Int,
) {
    DOH("https", 443),
    DOT("tls", 853),
    PLAIN("udp", 53),
}

@Serializable
enum class DnsFilterMode {
    COMPATIBILITY,
    STRICT,
}

@Serializable
enum class PerAppRoutingMode {
    FULL_TUNNEL,
    INCLUDE_SELECTED_APPS,
    EXCLUDE_SELECTED_APPS,
}

@Serializable
enum class PrivacyRouteMode {
    OFF,
    TOR_OVER_VPN,
}

@Serializable
enum class PrivacyRouteScope {
    SELECTED_APPS,
    ALL_APPS,
}

@Serializable
enum class DiagnosticsRetention(
    val retentionHours: Int,
    val maxEntries: Int,
) {
    HOURS_6(retentionHours = 6, maxEntries = 1_500),
    HOURS_24(retentionHours = 24, maxEntries = 5_000),
    DAYS_2(retentionHours = 48, maxEntries = 7_000),
    DAYS_3(retentionHours = 72, maxEntries = 9_000),
    DAYS_7(retentionHours = 168, maxEntries = 15_000),
    DAYS_14(retentionHours = 336, maxEntries = 20_000),
    DAYS_30(retentionHours = 720, maxEntries = 30_000),
}

@Serializable
enum class StatisticsRetention {
    WEEK,
    MONTH,
    MONTHS_3,
    FOREVER,
}

@Serializable
enum class StatisticsRefreshInterval(val seconds: Int) {
    SECONDS_1(1),
    SECONDS_3(3),
    SECONDS_5(5),
    SECONDS_10(10),
}

enum class StatisticsMetric {
    PROFILE_TRAFFIC,
    VPN_PROTOCOLS,
    PROFILE_COMPARISONS,
    TRANSPORTS,
    APP_TRAFFIC,
    DNS_FILTERING,
    COUNTRY_TRAFFIC,
    ANOMALIES,
    APP_CHANGES,
}

@Serializable
enum class RoutingPresetSource {
    LOCAL,
    FILE,
    REMOTE,
}

@Serializable
enum class RoutingPresetOverrideMode {
    RESPECT_PROFILE,
    FORCE_LOCAL,
}

@Serializable
enum class RoutingRuleAction(val outboundTag: String) {
    PROXY("proxy"),
    DIRECT("direct"),
    BLOCK("block"),
}

@Serializable
data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val locale: AppLocale = AppLocale.SYSTEM,
    val onboardingCompleted: Boolean = true,
    val showExpertSettings: Boolean = false,
    val supportBotHandleOverride: String? = null,
    val networkCardEnabled: Boolean = true,
    val trafficCardEnabled: Boolean = true,
    val trafficMapEnabled: Boolean = false,
    val showFirewallStatus: Boolean = false,
    val showTorQuickLaunch: Boolean = false,
    val smartStartDashboardControlsEnabled: Boolean = true,
    val dashboardCardOrder: List<DashboardCard> = DashboardCard.entries,
)

@Serializable
data class ConnectionSettings(
    val autoReconnect: Boolean = true,
    val autoStartOnBoot: Boolean = false,
    val autoRefreshSubscriptions: Boolean = false,
    val subscriptionRefreshInterval: SubscriptionRefreshInterval = SubscriptionRefreshInterval.HOURS_6,
    val ipInfoEndpoint: String = "",
    val latencyProbeMethod: LatencyProbeMethod = LatencyProbeMethod.HTTP,
    val smartStartProtocolSelectionTimeoutSeconds: Int = SMART_START_PROTOCOL_TIMEOUT_DEFAULT_SECONDS,
    val smartStartRefreshSelectionTimeoutSeconds: Int = SMART_START_REFRESH_TIMEOUT_DEFAULT_SECONDS,
    val smartStartTransportPriority: SmartStartTransportPriority = SmartStartTransportPriority.ALL,
    val smartStartV2RayTunSubscriptionsEnabled: Boolean = true,
    val smartStartFailoverEnabled: Boolean = true,
    val smartStartSubscriptionRetryAttempts: Int = SMART_START_SUBSCRIPTION_RETRY_ATTEMPTS_DEFAULT,
    val smartStartSubscriptionRetryDelaySeconds: Int = SMART_START_SUBSCRIPTION_RETRY_DELAY_DEFAULT_SECONDS,
    @SerialName("stealthModeEnabled")
    val safeModeEnabled: Boolean = true,
)

@Serializable
data class CachedActiveProfile(
    val id: Long,
    val name: String,
    val sourceType: ProfileSourceType,
    val protocolHint: ProtocolHint,
)

@Serializable
data class TrafficSettings(
    val mode: TrafficMode = TrafficMode.TUNNEL,
    val tunStack: TunStack = TunStack.SYSTEM,
    val mtu: Int = 1500,
    val preferIpv6: Boolean = false,
    val domainStrategy: DomainStrategy = DomainStrategy.PREFER_IPV4,
)

@Serializable
data class DnsSettings(
    val dnsThroughVpn: Boolean = true,
    val blockOutsideTunnel: Boolean = true,
    val interceptDnsRequests: Boolean = true,
    val server: String = "1.1.1.1",
    val secureMode: SecureDnsMode = SecureDnsMode.PLAIN,
    val filteringEnabled: Boolean = false,
    val filterMode: DnsFilterMode = DnsFilterMode.COMPATIBILITY,
    val blockAds: Boolean = true,
    val blockTrackers: Boolean = true,
    val blockAppTelemetry: Boolean = true,
    val blockMaliciousDomains: Boolean = true,
    val autoUpdateFilters: Boolean = true,
    val appBypassPackages: List<String> = emptyList(),
    val domainBypassRules: List<String> = emptyList(),
    val filtersUpdatedAt: Long? = null,
)

@Serializable
data class NetworkRulesSettings(
    val wifiRulesEnabled: Boolean = true,
    val cellularRulesEnabled: Boolean = true,
    val skipSubscriptionRefreshOnCellular: Boolean = true,
    val skipSpeedTestsOnCellular: Boolean = true,
    val useWifiProfile: Boolean = false,
    val wifiProfileId: Long? = null,
    val wifiProtocolOptionId: String? = null,
    val useCellularProfile: Boolean = false,
    val cellularProfileId: Long? = null,
)

@Serializable
data class PrivacyRouteSettings(
    val mode: PrivacyRouteMode = PrivacyRouteMode.OFF,
    val scope: PrivacyRouteScope = PrivacyRouteScope.SELECTED_APPS,
    val selectedPackages: List<String> = emptyList(),
    val bypassVpnTunnel: Boolean = false,
    val identityVersion: Long = 0L,
) {
    val enabled: Boolean
        get() = mode == PrivacyRouteMode.TOR_OVER_VPN

    val directTorEnabled: Boolean
        get() = enabled && bypassVpnTunnel
}

@Serializable
data class ProxyInboundSettings(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 10808,
)

@Serializable
data class LocalAuthSettings(
    val enabled: Boolean = true,
    val username: String = "",
    val password: String = "",
    val apiSecret: String = "",
)

@Serializable
data class ClashApiSettings(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 9090,
    val secret: String = "",
)

@Serializable
data class V2RayApiSettings(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 10085,
    val statsEnabled: Boolean = false,
)

@Serializable
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
data class ExpertSettings(
    val unlockedAt: Long? = null,
    val warningAcknowledgedAt: Long? = null,
    val blockScreenshots: Boolean = false,
    val killSwitchEnabled: Boolean = false,
    val firewallEnabled: Boolean = false,
    val newAppQuarantineEnabled: Boolean = false,
    val systemDnsProtectionEnabled: Boolean = false,
    val networkActivityLogging: Boolean = false,
    val networkActivityPersistentLogging: Boolean = false,
    val diagnosticsRetention: DiagnosticsRetention = DiagnosticsRetention.HOURS_24,
    val smartStartReplayLogging: Boolean = false,
    val allowInsecureTls: Boolean = false,
    val sniff: Boolean = true,
    val routeOnly: Boolean = false,
    val strictRoute: Boolean = true,
    val bypassLan: Boolean = false,
    val allowPrivateOutboundHosts: Boolean = false,
    val perAppRoutingMode: PerAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
    val selectedPackages: List<String> = emptyList(),
    val blockedPackages: List<String> = emptyList(),
    val blockedPackagesEnabled: Boolean = false,
    val blockAppsAlways: Boolean = false,
    val siteRoutingAction: RoutingRuleAction = RoutingRuleAction.PROXY,
    val localSurfaces: LocalSurfaceSettings = LocalSurfaceSettings(),
)

@Serializable
data class StatisticsSettings(
    val enabled: Boolean = false,
    val retention: StatisticsRetention = StatisticsRetention.FOREVER,
    val refreshInterval: StatisticsRefreshInterval = StatisticsRefreshInterval.SECONDS_3,
    val profileTrafficEnabled: Boolean = true,
    val vpnProtocolsEnabled: Boolean = false,
    val profileComparisonsEnabled: Boolean = false,
    val transportsEnabled: Boolean = false,
    val appTrafficEnabled: Boolean = false,
    val dnsFilteringEnabled: Boolean = false,
    val countryTrafficEnabled: Boolean = false,
    val anomalyMetricsEnabled: Boolean = false,
    val appChangesEnabled: Boolean = false,
)

@Serializable
enum class InstalledAppChangeType {
    INSTALLED,
    REMOVED,
}

@Serializable
enum class InstalledAppRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
enum class InstalledAppRiskSignal {
    ACCESSIBILITY_SERVICE,
    NOTIFICATION_LISTENER,
    DEVICE_ADMIN,
    VPN_SERVICE,
    OVERLAY_PERMISSION,
    BATTERY_OPTIMIZATION_IGNORE,
    AUTOSTART,
    UNKNOWN_INSTALLER,
    SYSTEM_LIKE_NAME,
}

@Serializable
data class InstalledAppInventoryEntry(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean = false,
    val installerPackageName: String? = null,
    val riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
    val riskSignals: List<InstalledAppRiskSignal> = emptyList(),
)

@Serializable
data class InstalledAppInventoryChange(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean = false,
    val type: InstalledAppChangeType,
    val detectedAt: Long,
    val installerPackageName: String? = null,
    val riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
    val riskSignals: List<InstalledAppRiskSignal> = emptyList(),
)

@Serializable
data class InstalledAppInventoryAudit(
    val capturedAt: Long = 0L,
    val packages: List<InstalledAppInventoryEntry> = emptyList(),
    val recentChanges: List<InstalledAppInventoryChange> = emptyList(),
)

@Serializable
data class Settings(
    val schemaVersion: Int = SETTINGS_SCHEMA_VERSION,
    val ui: UiSettings = UiSettings(),
    val connection: ConnectionSettings = ConnectionSettings(),
    val traffic: TrafficSettings = TrafficSettings(),
    val dns: DnsSettings = DnsSettings(),
    val networkRules: NetworkRulesSettings = NetworkRulesSettings(),
    val privacyRoute: PrivacyRouteSettings = PrivacyRouteSettings(),
    val expert: ExpertSettings = ExpertSettings(),
    val statistics: StatisticsSettings = StatisticsSettings(),
    val anomaly: AnomalySettings = AnomalySettings(),
    val lastActiveProfile: CachedActiveProfile? = null,
    val smartProfilePreferences: List<SmartProfilePreference> = emptyList(),
    val profileTrafficTotals: List<ProfileTrafficTotal> = emptyList(),
    val installedAppInventoryAudit: InstalledAppInventoryAudit = InstalledAppInventoryAudit(),
    val appTrafficStatsEnabled: Boolean = false,
    val usageTrackingStartedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SmartProfilePreference(
    val profileId: Long,
    val excludedProtocolOptionIds: List<String> = emptyList(),
    val lastKnownGoodOptionId: String? = null,
    val lastKnownGoodLatencyMs: Long? = null,
    val lastKnownGoodAt: Long? = null,
    val lastFullSmartRefreshAt: Long? = null,
    val smartStartBaselineReady: Boolean = false,
    val recommendedProtocolIds: List<String> = emptyList(),
    val enabledProtocolSetHash: String? = null,
    val networkFingerprintSchema: Int = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
    val protocolMemories: List<SmartProfileProtocolMemory> = emptyList(),
    val networkMemories: List<SmartProfileNetworkMemory> = emptyList(),
)

@Serializable
data class SmartProfileNetworkMemory(
    val networkFingerprint: String,
    val networkFingerprintSchema: Int = NETWORK_FINGERPRINT_SCHEMA_LEGACY,
    val lastKnownGoodOptionId: String? = null,
    val lastKnownGoodLatencyMs: Long? = null,
    val lastKnownGoodAt: Long? = null,
    val protocolMemories: List<SmartProfileProtocolMemory> = emptyList(),
)

@Serializable
data class SmartProfileProtocolMemory(
    val optionId: String,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val lastLatencyMs: Long? = null,
    val lastServerPingMs: Long? = null,
    val lastServerPingAt: Long? = null,
    val lastReasonCode: AutoConnectReasonCode? = null,
    val failureStreak: Int = 0,
    val validationFailureCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastConnectDurationMs: Long? = null,
    val lastValidatedAt: Long? = null,
    val lastTrafficAt: Long? = null,
    val cooldownUntilAt: Long? = null,
)

data class InstalledAppOption(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val installerPackageName: String? = null,
    val riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
    val riskSignals: List<InstalledAppRiskSignal> = emptyList(),
)

data class Profile(
    val id: Long,
    val name: String,
    val sourceType: ProfileSourceType,
    val secretRef: String,
    val protocolHint: ProtocolHint,
    val lastUpdatedAt: Long?,
    val lastEtag: String?,
    val subscriptionExpiresAt: Long? = null,
    val protocolOptions: List<ProfileProtocolOption> = emptyList(),
    val selectedProtocolOptionId: String? = null,
    val requiresInsecureTls: Boolean = false,
    val insecureTlsConsentGranted: Boolean = false,
    val isActive: Boolean,
)

data class ProfileProtocolOption(
    val id: String,
    val displayName: String,
    val protocolHint: ProtocolHint,
    val requiresInsecureTls: Boolean = false,
    val isSelected: Boolean = false,
)

data class RoutingRule(
    val id: Long,
    val presetId: Long,
    val name: String,
    val enabled: Boolean,
    val order: Int,
    val action: RoutingRuleAction,
    val matchDomains: List<String>,
    val matchIpCidrs: List<String>,
    val matchPorts: List<String>,
    val matchProtocols: List<String>,
    val matchNetworks: List<String>,
)

data class RoutingPreset(
    val id: Long,
    val name: String,
    val source: RoutingPresetSource,
    val catalogId: Long? = null,
    val overrideMode: RoutingPresetOverrideMode = RoutingPresetOverrideMode.RESPECT_PROFILE,
    val enabled: Boolean = true,
    val updatedAt: Long,
    val isActive: Boolean = false,
    val rules: List<RoutingRule> = emptyList(),
)

data class RoutingCatalog(
    val id: Long,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val etag: String?,
    val lastSyncAt: Long?,
    val warningAcceptedAt: Long?,
    val cachedPresetCount: Int = 0,
)

data class IpInfo(
    val ip: String,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val localDnsServers: List<String> = emptyList(),
    val remoteDnsServers: List<String> = emptyList(),
    val countryCode: String?,
    val countryName: String?,
    val city: String?,
    val isp: String?,
    val fetchedAt: Long,
)

@Serializable
data class ProfileTrafficTotal(
    val profileId: Long,
    val profileName: String,
    val protocolHint: ProtocolHint,
    val protocolOptionId: String? = null,
    val transport: TransportProtocol = TransportProtocol.UNKNOWN,
    val rxTotalBytes: Long = 0,
    val txTotalBytes: Long = 0,
    val updatedAt: Long = 0,
)

data class ConnectionSnapshot(
    val state: ConnectionState = ConnectionState.IDLE,
    val trafficMode: TrafficMode = TrafficMode.TUNNEL,
    val profileId: Long? = null,
    val profileName: String? = null,
    val protocolHint: ProtocolHint? = null,
    val protocolOptionId: String? = null,
    val message: String? = null,
    val reasonCode: AutoConnectReasonCode? = null,
    val isSmartStartConnection: Boolean = false,
    val lastChangeAt: Long = System.currentTimeMillis(),
)

data class TrafficSnapshot(
    val available: Boolean = false,
    val rxBytesPerSec: Long = 0,
    val txBytesPerSec: Long = 0,
    val rxTotalBytes: Long = 0,
    val txTotalBytes: Long = 0,
    val sampledAt: Long = 0,
)

data class NotificationSnapshot(
    val profileName: String? = null,
    val state: ConnectionState = ConnectionState.IDLE,
    val statusMessage: String? = null,
    val connectivityHealthState: ConnectivityHealthState = ConnectivityHealthState.CHECKING,
    val ipAddress: String? = null,
    val countryCode: String? = null,
    val countryName: String? = null,
    val trafficAvailable: Boolean = false,
    val txRate: Long = 0,
    val rxRate: Long = 0,
    val txTotal: Long = 0,
    val rxTotal: Long = 0,
    val updatedAt: Long = 0,
    val isSmartStartConnection: Boolean = false,
    val isRedacted: Boolean = false,
)

data class ParsedImport(
    val sourceType: ProfileSourceType,
    val protocolHint: ProtocolHint,
    val displayName: String,
    val normalizedConfigJson: String?,
    val sourceUrl: String? = null,
    val nodesCount: Int = 1,
    val subscriptionExpiresAt: Long? = null,
    val protocolOptions: List<StoredProfileProtocolOption> = emptyList(),
    val selectedProtocolOptionId: String? = null,
)

data class ParsedSubscriptionProfile(
    val displayName: String,
    val protocolHint: ProtocolHint,
    val normalizedConfigJson: String,
    val subscriptionExpiresAt: Long? = null,
    val protocolOptions: List<StoredProfileProtocolOption> = emptyList(),
    val selectedProtocolOptionId: String? = null,
)

data class ParsedSubscriptionImport(
    val displayName: String,
    val profiles: List<ParsedSubscriptionProfile>,
    val subscriptionExpiresAt: Long? = null,
) {
    init {
        require(profiles.isNotEmpty()) { "subscription must contain at least one profile" }
    }

    val nodesCount: Int
        get() = profiles.size
}

@Serializable
data class StoredProfileSecret(
    val rawInput: String? = null,
    val subscriptionUrl: String? = null,
    val resolvedConfigJson: String? = null,
    val subscriptionExpiresAt: Long? = null,
    val protocolOptions: List<StoredProfileProtocolOption> = emptyList(),
    val selectedProtocolOptionId: String? = null,
    val requiresInsecureTls: Boolean = false,
    val insecureTlsConsentGranted: Boolean? = null,
)

@Serializable
data class StoredProfileProtocolOption(
    val id: String,
    val displayName: String,
    val protocolHint: ProtocolHint,
    val normalizedConfigJson: String,
    val requiresInsecureTls: Boolean = false,
)

data class VpnSession(
    val profileId: Long,
    val profileName: String,
    val protocolHint: ProtocolHint,
    val protocolOptionId: String? = null,
    val configJson: String,
    val correlationId: String,
)
