package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

const val SETTINGS_SCHEMA_VERSION = 19
const val NETWORK_FINGERPRINT_SCHEMA_CURRENT = 2
const val NETWORK_FINGERPRINT_SCHEMA_LEGACY = 1
const val DEFAULT_DNS_FILTER_UPDATE_URL = "https://foxhole-team.github.io/foxhole-db/manifest.json"

const val SMART_START_REFRESH_TIMEOUT_MIN_SECONDS = 10
const val SMART_START_REFRESH_TIMEOUT_DEFAULT_SECONDS = 15
const val SMART_START_TIMEOUT_MAX_SECONDS = 60

@Serializable
@Immutable
data class SmartProfilePreference(
    val profileId: Long,
    val lastKnownGoodOptionId: String? = null,
    val lastKnownGoodLatencyMs: Long? = null,
    val lastKnownGoodAt: Long? = null,
    val lastFullSmartRefreshAt: Long? = null,
    val recommendedProtocolIds: List<String> = emptyList(),
    val enabledProtocolSetHash: String? = null,
    val networkFingerprintSchema: Int = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
    val protocolMemories: List<SmartProfileProtocolMemory> = emptyList(),
    val networkMemories: List<SmartProfileNetworkMemory> = emptyList(),
)

@Serializable
@Immutable
data class SmartProfileNetworkMemory(
    val networkFingerprint: String,
    val networkFingerprintSchema: Int = NETWORK_FINGERPRINT_SCHEMA_LEGACY,
    val lastKnownGoodOptionId: String? = null,
    val lastKnownGoodLatencyMs: Long? = null,
    val lastKnownGoodAt: Long? = null,
    val protocolMemories: List<SmartProfileProtocolMemory> = emptyList(),
)

@Serializable
@Immutable
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

@Immutable
data class InstalledAppOption(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val versionCode: Long? = null,
    val firstInstallTime: Long? = null,
    val lastUpdateTime: Long? = null,
    val installerPackageName: String? = null,
    val riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
    val riskSignals: List<InstalledAppRiskSignal> = emptyList(),
)

@Immutable
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

@Immutable
data class ProfileProtocolOption(
    val id: String,
    val displayName: String,
    val protocolHint: ProtocolHint,
    val requiresInsecureTls: Boolean = false,
    val isSelected: Boolean = false,
    // Per-protocol on/off inside a smart profile (N1). Persisted in the profile secret; a disabled
    // option stays in the list but cannot be picked as the running protocol until re-enabled.
    val enabled: Boolean = true,
)

@Immutable
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

@Immutable
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

@Immutable
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

@Immutable
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
@Immutable
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

@Immutable
data class ConnectionSnapshot(
    val state: ConnectionState = ConnectionState.IDLE,
    val teardownPhase: RuntimeTeardownPhase? = null,
    val trafficMode: TrafficMode = TrafficMode.TUNNEL,
    val profileId: Long? = null,
    val profileName: String? = null,
    val protocolHint: ProtocolHint? = null,
    val protocolOptionId: String? = null,
    // True only when the *applied* runtime config actually carries the Tor route (in-tunnel or
    // beside) or the runtime IS the standalone Tor-only session. The settings toggle merely arms
    // the route; status labels/badges must report engagement from this flag, never from settings.
    val torActive: Boolean = false,
    val message: String? = null,
    val reasonCode: AutoConnectReasonCode? = null,
    // A service-owned hot apply is validating an already-live runtime. This is deliberately false
    // for genuine reconnects and cold restarts so presentation reducers can retain proven route
    // identity without hiding a real connection transition.
    val inPlaceRuntimeReload: Boolean = false,
    val isSmartStartConnection: Boolean = false,
    val upstreamNetworkRevision: Long = 0L,
    val lastChangeAt: Long = System.currentTimeMillis(),
)

@Immutable
data class TrafficSnapshot(
    val available: Boolean = false,
    val rxBytesPerSec: Long = 0,
    val txBytesPerSec: Long = 0,
    val rxTotalBytes: Long = 0,
    val txTotalBytes: Long = 0,
    val sampledAt: Long = 0,
)

@Immutable
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

@Immutable
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
    val entryReports: List<SubscriptionEntryReport> = emptyList(),
)

@Immutable
data class ParsedSubscriptionProfile(
    val displayName: String,
    val protocolHint: ProtocolHint,
    val normalizedConfigJson: String,
    val subscriptionExpiresAt: Long? = null,
    val protocolOptions: List<StoredProfileProtocolOption> = emptyList(),
    val selectedProtocolOptionId: String? = null,
)

enum class SubscriptionEntryStatus {
    ACCEPTED,
    IGNORED_UNSUPPORTED,
}

@Immutable
data class SubscriptionEntryReport(
    val protocolLabel: String,
    val protocolHint: ProtocolHint,
    val status: SubscriptionEntryStatus,
    val sourceLine: Int,
    val reason: String? = null,
)

@Immutable
data class ParsedSubscriptionImport(
    val displayName: String,
    val profiles: List<ParsedSubscriptionProfile>,
    val subscriptionExpiresAt: Long? = null,
    val entryReports: List<SubscriptionEntryReport> = emptyList(),
) {
    init {
        require(profiles.isNotEmpty()) { "subscription must contain at least one profile" }
    }

    val nodesCount: Int
        get() = profiles.size
}

@Serializable
@Immutable
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
@Immutable
data class StoredProfileProtocolOption(
    val id: String,
    val displayName: String,
    val protocolHint: ProtocolHint,
    val normalizedConfigJson: String,
    val requiresInsecureTls: Boolean = false,
    // Persisted per-protocol on/off (N1). Defaults to true so profiles serialized before this field
    // existed deserialize as fully enabled.
    val enabled: Boolean = true,
)

/**
 * Strict Rust-engine documents plus the Android-owned TUN plan produced at the migration boundary.
 *
 * Secrets can be present in the engine document, so the value deliberately has a redacted
 * [toString]. It is an in-memory session contract, not a persistence DTO.
 */
@Immutable
data class FoxCoreSessionConfig(
    val engineConfigJson: String,
    val policyConfigJson: String,
    val tunPlan: FoxCoreTunPlan,
    val dnsRuleSetBootstrap: FoxCoreDnsRuleSetBootstrap? = null,
) {
    override fun toString(): String =
        "FoxCoreSessionConfig(engineConfigJson=<redacted>, " +
            "policyConfigJson=<redacted>, tunPlan=$tunPlan, " +
            "dnsRuleSetBootstrap=${dnsRuleSetBootstrap?.redactedDescription()})"
}

/**
 * Non-JSON bootstrap for the DNS FST covered by a verified FoxHole DB manifest signature.
 *
 * The update verification key is pinned in the prepared FoxCore DNS policy;
 * artifact bytes are read separately and never enter profile JSON, logs or
 * backups.
 */
data class FoxCoreDnsRuleSetBootstrap(
    val name: String,
    val artifactPath: String,
    val artifactSha256: String,
    val publicKeyBase64: String,
    val minimumSequence: Long = 0,
    val signedUpdate: FoxCoreSignedDnsRuleSetUpdate? = null,
) {
    internal fun redactedDescription(): String =
        "FoxCoreDnsRuleSetBootstrap(name=$name, artifact=<redacted>, key=<redacted>)"

    override fun toString(): String = redactedDescription()
}

data class FoxCoreSignedDnsRuleSetUpdate(
    val manifestPath: String,
    val signaturePath: String,
    val artifactPath: String,
) {
    override fun toString(): String = "FoxCoreSignedDnsRuleSetUpdate(<redacted>)"
}

@Immutable
data class FoxCoreTunPlan(
    val mtu: Int,
    val ipv4Address: String,
    val ipv4PrefixLength: Int,
    val ipv6Address: String?,
    val ipv6PrefixLength: Int?,
    val routes: List<FoxCoreTunRoute>,
    val advertisedDnsServers: List<String>,
    val allowedApplications: List<String> = emptyList(),
    val disallowedApplications: List<String> = emptyList(),
)

@Immutable
data class FoxCoreTunRoute(
    val address: String,
    val prefixLength: Int,
)

@Immutable
data class VpnSession(
    val profileId: Long,
    val profileName: String,
    val protocolHint: ProtocolHint,
    val protocolOptionId: String? = null,
    val configJson: String,
    val correlationId: String,
    /**
     * The only configuration the native data plane is allowed to consume. [configJson] remains
     * temporarily for app-side validation and one-shot migration of saved profiles; it is never
     * passed to JNI.
     */
    val foxCoreConfig: FoxCoreSessionConfig? = null,
    // True when this session's assembled config carries the Tor route (or is the Tor-only
    // runtime); flows into ConnectionSnapshot.torActive on connect/reload.
    val torActive: Boolean = false,
    /**
     * Full-capture local guards must return fake addresses to Android and keep the original host
     * attached to the flow. The narrow DNS-only guard cannot use fake addresses because it does
     * not route the later application connection through its TUN.
     */
    val forceFakeIpDns: Boolean = false,
    /**
     * Native, first-packet quarantine. When armed, FoxCore rejects every attributed package that
     * is absent from [knownApplications]; the PackageManager broadcast and Sentinel analysis are
     * deliberately not on the enforcement path.
     */
    val quarantineNewApps: Boolean = false,
    val knownApplications: List<KnownApplicationIdentity> = emptyList(),
    /** Fingerprint captured from the same immutable inputs that assembled [configJson]. */
    val runtimeConfigFingerprint: Int? = null,
    /** Durable quarantine/BLOCK revision captured together with this exact session. */
    val quarantinePolicyRevision: Long = 0L,
    /** Generation of the exact authenticated i2pd endpoint embedded in this config. */
    val i2pEndpointGeneration: Long? = null,
)

@Serializable
@Immutable
data class KnownApplicationIdentity(
    val packageName: String,
    val signingCertificateSha256: String? = null,
    val firstSeenAtMs: Long? = null,
)
