package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

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
@Immutable
data class DnsSettings(
    val useVpnProviderDns: Boolean = true,
    val dnsThroughVpn: Boolean = true,
    val blockOutsideTunnel: Boolean = true,

    val interceptDnsRequests: Boolean = false,
    // Uses the tunnel resolver or standalone DNS guard; OFF by default, so system DNS is never silently rewritten.
    val replaceSystemDns: Boolean = false,
    val server: String = "1.1.1.1",
    val secureMode: SecureDnsMode = SecureDnsMode.PLAIN,
    val filteringEnabled: Boolean = false,
    val blockAds: Boolean = true,
    val blockTrackers: Boolean = true,
    val blockAppTelemetry: Boolean = true,
    val blockMaliciousDomains: Boolean = true,
    val trackerListLevel: TrackerListLevel = TrackerListLevel.NORMAL,
    val threatListLevel: ThreatListLevel = ThreatListLevel.MEDIUM,
    val autoUpdateFilters: Boolean = false,
    val dnsFilterUpdateUrl: String = DEFAULT_DNS_FILTER_UPDATE_URL,
    val appBypassPackages: List<String> = emptyList(),
    val domainBypassRules: List<String> = emptyList(),
    val filtersUpdatedAt: Long? = null,
    val filtersCheckedAt: Long? = null,
)

fun DnsSettings.dnsRuleSetFilteringEnabled(): Boolean =
    filteringEnabled && (blockAds || blockTrackers || blockAppTelemetry || blockMaliciousDomains)

@Serializable
enum class DnsFilterCategory {
    MALICIOUS,
    TELEMETRY,
    TRACKERS,
    ADS,
}

@Serializable
enum class TrackerListLevel {
    NORMAL,
    PRO,
}

@Serializable
enum class ThreatListLevel {
    MEDIUM,
    FULL,
}

val DnsFilterCategory.dnsRuleSetTag: String
    get() = "foxhole-dns-" + name.lowercase()

fun DnsSettings.dnsRuleSetSourceTag(category: DnsFilterCategory): String =
    when (category) {
        DnsFilterCategory.ADS -> "foxhole-adguard-dns-filter"
        DnsFilterCategory.TELEMETRY -> "foxhole-hagezi-native-android"
        DnsFilterCategory.TRACKERS ->
            when (trackerListLevel) {
                TrackerListLevel.NORMAL -> "foxhole-hagezi-multi-normal"
                TrackerListLevel.PRO -> "foxhole-hagezi-multi-pro"
            }
        DnsFilterCategory.MALICIOUS ->
            when (threatListLevel) {
                ThreatListLevel.MEDIUM -> "foxhole-hagezi-tif-medium"
                ThreatListLevel.FULL -> "foxhole-hagezi-tif-full"
            }
    }

fun DnsSettings.requestedDnsRuleSetTags(): Map<DnsFilterCategory, String> =
    enabledDnsFilterCategories().associateWith { category -> dnsRuleSetSourceTag(category) }

fun DnsSettings.enabledDnsFilterCategories(): Set<DnsFilterCategory> =
    if (!filteringEnabled) {
        emptySet()
    } else {
        buildSet {
            if (blockMaliciousDomains) add(DnsFilterCategory.MALICIOUS)
            if (blockAppTelemetry) add(DnsFilterCategory.TELEMETRY)
            if (blockTrackers) add(DnsFilterCategory.TRACKERS)
            if (blockAds) add(DnsFilterCategory.ADS)
        }
    }

fun DnsSettings.disableUnverifiedRuleSetRuntimeDns(): DnsSettings =
    if (filteringEnabled) {
        copy(filteringEnabled = false)
    } else {
        this
    }
