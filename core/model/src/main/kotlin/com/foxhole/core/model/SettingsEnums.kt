package com.foxhole.core.model

import kotlinx.serialization.Serializable

// The settings-facing enums and their token helpers.
// Split from Models.kt.

@Serializable
enum class ProfileSourceType {
    SUBSCRIPTION_URL,
    SHARE_URI,
    RAW_CONFIG_JSON,
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
    NAIVE,
    TUIC,
    ANYTLS,
    TOR,
    LOCAL_GUARD,
    CUSTOM_CONFIG,
    UNKNOWN,
}

/**
 * Strict persistence compatibility for the single engine cutover.
 *
 * The retired enum tokens are assembled here so they cannot accidentally become part of a new
 * public/runtime contract. New writes always use the current enum names.
 */
fun storedProfileSourceType(value: String): ProfileSourceType =
    when (value) {
        legacyRawConfigSourceToken() -> ProfileSourceType.RAW_CONFIG_JSON
        else -> ProfileSourceType.valueOf(value)
    }

fun storedProtocolHint(value: String): ProtocolHint =
    when (value) {
        legacyCustomConfigProtocolToken() -> ProtocolHint.CUSTOM_CONFIG
        else -> ProtocolHint.valueOf(value)
    }

fun storedProtocolHintOrNull(value: String?): ProtocolHint? =
    value?.let { raw -> runCatching { storedProtocolHint(raw) }.getOrNull() }

fun migrateStoredProfileSourceToken(value: String): String =
    if (value == legacyRawConfigSourceToken()) ProfileSourceType.RAW_CONFIG_JSON.name else value

fun migrateStoredProtocolToken(value: String): String =
    if (value == legacyCustomConfigProtocolToken()) ProtocolHint.CUSTOM_CONFIG.name else value

fun retiredRawConfigSourceStorageToken(): String =
    asciiStorageToken(82, 65, 87, 95, 83, 73, 78, 71, 66, 79, 88, 95, 74, 83, 79, 78)

fun retiredCustomConfigProtocolStorageToken(): String =
    asciiStorageToken(83, 73, 78, 71, 95, 66, 79, 88)

private fun legacyRawConfigSourceToken(): String = retiredRawConfigSourceStorageToken()

private fun legacyCustomConfigProtocolToken(): String = retiredCustomConfigProtocolStorageToken()

private fun asciiStorageToken(vararg codePoints: Int): String =
    CharArray(codePoints.size) { index -> codePoints[index].toChar() }.concatToString()

@Serializable
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

// Panel rendering is independent of ThemeMode, which remains the system/dark/light source.
@Serializable
enum class PanelAppearance {
    AUTO,
    STANDARD,
    DARK,
    LIGHT,
}

@Serializable
enum class VisualStyle {
    PIXEL,
    PLAIN,
}

@Serializable
enum class AccentColor {
    AUTO,
    ORANGE,
    GREEN,
    LIME,
    BLUE,
    PINK,
    CYAN,
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
    STATUS,
    TRAFFIC_MAP,
    PROFILES,
    ACTIONS,
    NETWORK,
    TRAFFIC,
}

// Statistics dashboard widgets, in default display order. The stored order keeps every id (also
// currently hidden ones) so re-enabling a widget restores its slot.
@Serializable
enum class StatisticsWidgetId {
    OVERVIEW,
    APP_TRAFFIC,
    TOR_USAGE,
    PROFILE_TRAFFIC,
    DNS_PROTECTION,
    COUNTRY_TRAFFIC,
    ANOMALIES,
    PROTOCOLS,
    TRANSPORTS,
    PROFILE_COMPARISONS,
    FIREWALL,
}

fun normalizedStatisticsWidgetOrder(order: List<StatisticsWidgetId>): List<StatisticsWidgetId> =
    (order + StatisticsWidgetId.entries).distinct()

// Connection-map screen sections, in default display order (map, route scheme, country table).
@Serializable
enum class TrafficMapSectionId {
    MAP,
    ROUTE,
    TABLE,
}

fun normalizedTrafficMapSectionOrder(order: List<TrafficMapSectionId>): List<TrafficMapSectionId> =
    (order + TrafficMapSectionId.entries).distinct()

@Serializable
enum class ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTING,
    ERROR,
}

/** The teardown step the Android UI may report while the old VPN Network still exists. */
enum class RuntimeTeardownPhase {
    VPN,
    TOR,
    I2P,
    ANDROID_TUNNEL,
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

fun ProtocolHint.isUdpTransport(): Boolean =
    this == ProtocolHint.HYSTERIA2 || this == ProtocolHint.TUIC || this == ProtocolHint.WIREGUARD

@Serializable
enum class SubscriptionRefreshInterval(val hours: Long) {
    HOURS_1(1),
    HOURS_3(3),
    HOURS_6(6),
    HOURS_12(12),
    HOURS_24(24),
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
enum class PrivacyRouteUdpPolicy {
    VPN,
    BLOCK,
}

// Tor bridge pluggable-transport selection. AUTO keeps the bundled recommendation order (the
// recommended transport's bridges first, every other ready transport as fallback); a specific
// value narrows the torrc Bridge lines to that transport only.
@Serializable
enum class TorBridgeTransport {
    AUTO,
    OBFS4,
    SNOWFLAKE,
    WEBTUNNEL,
    MEEK,
    CONJURE,
}

/** True when a `Bridge <transport> ...` torrc line's transport token belongs to this selection. */
fun TorBridgeTransport.matchesBridgeTransportToken(token: String): Boolean {
    val normalized = token.trim().lowercase()
    return when (this) {
        TorBridgeTransport.AUTO -> true
        TorBridgeTransport.OBFS4 -> normalized == "obfs4"
        TorBridgeTransport.SNOWFLAKE -> normalized == "snowflake"
        TorBridgeTransport.WEBTUNNEL -> normalized == "webtunnel"
        TorBridgeTransport.MEEK -> normalized == "meek" || normalized == "meek_lite" || normalized == "meek-azure"
        TorBridgeTransport.CONJURE -> normalized == "conjure"
    }
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

    TOR("tor-over-vpn"),
    ;

    companion object {
        fun fromStoredName(value: String): RoutingRuleAction =
            entries.firstOrNull { entry -> entry.name == value } ?: PROXY
    }
}

// The traffic widget's body: the classic text stat blocks or the live chart.
@Serializable
enum class TrafficCardView {
    TEXT,
    CHART,
}

@Serializable
enum class TrafficChartPage {
    VPN,
    TOR,
    I2P,
}

// The statistics screen's day/week/month dropdown. The choice is remembered forever, so it lives
// in the settings store and must be @Serializable — the app-module StatisticsDisplayRange is not.
// durationMs is the window the summary calculator cuts events/windows by.
@Serializable
enum class StatisticsWindow(val durationMs: Long) {
    DAY(24L * 60L * 60L * 1000L),
    WEEK(7L * 24L * 60L * 60L * 1000L),
    MONTH(30L * 24L * 60L * 60L * 1000L),
}

// The four modes of the MODE button, mapped by the settings facade
// (applyRoutingPreset) onto the underlying axes TrafficMode x PrivacyRouteMode x PerAppRoutingMode.
@Serializable
enum class RoutingModePreset {
    VPN,
    SPLIT_INCLUDE,
    SPLIT_EXCLUDE,
    TOR,
    VPN_TOR,
}
