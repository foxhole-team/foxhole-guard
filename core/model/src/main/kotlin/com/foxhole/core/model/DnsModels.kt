package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

// The DNS vocabulary: resolver modes, the filter categories a block is attributed to, and the
// strictness levels that decide which published list fills a category.

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
    // Redirect apps that use a hard-coded resolver into the in-tunnel resolver. OFF by default:
    // the app never touches DNS the user did not route through it.
    val interceptDnsRequests: Boolean = false,
    // Constantly replace the system DNS with the selected provider while FoxHole runs — with a
    // tunnel it hijacks every query into the tunnel resolver, without one it runs the standalone
    // DNS guard. OFF by default (the app never silently rewrites system DNS).
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

// DNS filter categories, ordered by severity: overlapping domains are attributed to (and rule
// rules are matched against) the most severe category first.
@Serializable
enum class DnsFilterCategory {
    MALICIOUS,
    TELEMETRY,
    TRACKERS,
    ADS,
}

/** How strict the tracker list is: the HaGeZi Multi build the TRACKERS category installs. */
@Serializable
enum class TrackerListLevel {
    NORMAL,
    PRO,
}

/** How deep the malicious-domain list goes: the HaGeZi TIF build the MALICIOUS category installs. */
@Serializable
enum class ThreatListLevel {
    MEDIUM,
    FULL,
}

/**
 * Tag of the per-category FoxCore rule set as the runtime knows it: it names the rule set in the
 * prepared policy and is echoed in blocked-query events, which is how a block is attributed to
 * its category. Deliberately independent of which upstream list currently fills the category (see
 * [DnsSettings.dnsRuleSetSourceTag]) — switching the tracker list to Pro must not re-label the
 * category or invalidate the statistics collected under it.
 */
val DnsFilterCategory.dnsRuleSetTag: String
    get() = "foxhole-dns-" + name.lowercase()

/**
 * Tag of the published list that fills a category in the update channel's manifest, which is where
 * the level toggles land: TRACKERS picks a HaGeZi Multi build, MALICIOUS a TIF build, and the other
 * two categories have a single list each.
 */
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

/** The published lists this configuration wants on disk, keyed by the category each one fills. */
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

// An unverified rule set disables ONLY the filtering itself. It must never fail open on the
// leak controls: dropping dnsThroughVpn/blockOutsideTunnel here silently sent DNS (and stray
// packets) outside the tunnel whenever the filter list could not be verified.
fun DnsSettings.disableUnverifiedRuleSetRuntimeDns(): DnsSettings =
    if (filteringEnabled) {
        copy(filteringEnabled = false)
    } else {
        this
    }
