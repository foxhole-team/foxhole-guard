package com.foxhole.beta.core.model

import com.foxhole.beta.BuildConfig

import kotlinx.serialization.Serializable

const val SETTINGS_SCHEMA_VERSION = 10

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
enum class DomainStrategy(val configValue: String) {
    AS_IS("as_is"),
    PREFER_IPV4("prefer_ipv4"),
    PREFER_IPV6("prefer_ipv6"),
    IPV4_ONLY("ipv4_only"),
    IPV6_ONLY("ipv6_only"),
}

@Serializable
enum class PerAppRoutingMode {
    FULL_TUNNEL,
    INCLUDE_SELECTED_APPS,
    EXCLUDE_SELECTED_APPS,
}

@Serializable
enum class DiagnosticsRetention(
    val retentionHours: Int,
    val maxEntries: Int,
) {
    HOURS_6(retentionHours = 6, maxEntries = 1_500),
    HOURS_24(retentionHours = 24, maxEntries = 5_000),
    DAYS_3(retentionHours = 72, maxEntries = 9_000),
    DAYS_7(retentionHours = 168, maxEntries = 15_000),
    DAYS_14(retentionHours = 336, maxEntries = 20_000),
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
)

@Serializable
data class ConnectionSettings(
    val autoReconnect: Boolean = true,
    val autoStartOnBoot: Boolean = false,
    val ipInfoEndpoint: String = "",
    val stealthModeEnabled: Boolean = true,
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
    val socks: ProxyInboundSettings = ProxyInboundSettings(port = 10808),
    val http: ProxyInboundSettings = ProxyInboundSettings(port = 10809),
    val mixed: ProxyInboundSettings = ProxyInboundSettings(port = 10810),
    val allowLanAccess: Boolean = false,
    val clashApi: ClashApiSettings = ClashApiSettings(),
    val v2RayApi: V2RayApiSettings = V2RayApiSettings(),
    val auth: LocalAuthSettings = LocalAuthSettings(),
)

@Serializable
data class ExpertSettings(
    val unlockedAt: Long? = null,
    val warningAcknowledgedAt: Long? = null,
    val blockScreenshots: Boolean = false,
    val networkActivityLogging: Boolean = false,
    val diagnosticsRetention: DiagnosticsRetention = DiagnosticsRetention.HOURS_24,
    val allowHttpConfigImports: Boolean = false,
    val allowInsecureTls: Boolean = BuildConfig.ALLOW_INSECURE_TLS_BY_DEFAULT,
    val sniff: Boolean = false,
    val routeOnly: Boolean = false,
    val strictRoute: Boolean = true,
    val bypassLan: Boolean = false,
    val allowPrivateOutboundHosts: Boolean = false,
    val perAppRoutingMode: PerAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
    val selectedPackages: List<String> = emptyList(),
    val localSurfaces: LocalSurfaceSettings = LocalSurfaceSettings(),
)

@Serializable
data class Settings(
    val schemaVersion: Int = SETTINGS_SCHEMA_VERSION,
    val ui: UiSettings = UiSettings(),
    val connection: ConnectionSettings = ConnectionSettings(),
    val traffic: TrafficSettings = TrafficSettings(),
    val expert: ExpertSettings = ExpertSettings(),
    val lastActiveProfile: CachedActiveProfile? = null,
    val smartProfilePreferences: List<SmartProfilePreference> = emptyList(),
    val profileTrafficTotals: List<ProfileTrafficTotal> = emptyList(),
    val usageTrackingStartedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SmartProfilePreference(
    val profileId: Long,
    val excludedProtocolOptionIds: List<String> = emptyList(),
    val lastKnownGoodOptionId: String? = null,
    val lastKnownGoodLatencyMs: Long? = null,
    val lastKnownGoodAt: Long? = null,
    val protocolMemories: List<SmartProfileProtocolMemory> = emptyList(),
    val networkMemories: List<SmartProfileNetworkMemory> = emptyList(),
)

@Serializable
data class SmartProfileNetworkMemory(
    val networkFingerprint: String,
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
    val lastReasonCode: AutoConnectReasonCode? = null,
    val failureStreak: Int = 0,
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
    val isActive: Boolean,
)

data class ProfileProtocolOption(
    val id: String,
    val displayName: String,
    val protocolHint: ProtocolHint,
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
    val message: String? = null,
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
)

@Serializable
data class StoredProfileProtocolOption(
    val id: String,
    val displayName: String,
    val protocolHint: ProtocolHint,
    val normalizedConfigJson: String,
)

data class VpnSession(
    val profileId: Long,
    val profileName: String,
    val protocolHint: ProtocolHint,
    val configJson: String,
)
