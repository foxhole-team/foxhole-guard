package com.foxhole.guard.runtime

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ThreatListLevel
import com.foxhole.core.model.TrackerListLevel
import com.foxhole.core.model.enabledDnsFilterCategories
import okhttp3.HttpUrl

internal data class CategorizedDnsSelection(
    val tiers: CategorizedDnsTiers,
    val categories: Set<DnsFilterCategory>,
) {
    val variant: String get() = tiers.variant()
    val name: String get() = CategorizedDnsContract.NAME_PREFIX + variant

    fun requireAvailable(manifest: CategorizedDnsManifest) {
        require(manifest.name == name && manifest.tiers == tiers) { "requested DNS tier is unavailable" }
        require(
            CategorizedDnsContract.categories(manifest).containsAll(categories)
        ) { "required DNS category is unavailable" }
    }

    fun manifestUrl(base: HttpUrl, officialLegacyUrl: HttpUrl): HttpUrl =
        if (base == officialLegacyUrl) {
            requireNotNull(base.resolve("dns-categories/$variant/manifest.json"))
        } else {
            base
        }
}

internal fun DnsSettings.categorizedDnsSelection(): CategorizedDnsSelection = CategorizedDnsSelection(
    CategorizedDnsTiers(
        trackers = when (trackerListLevel) {
            TrackerListLevel.NORMAL -> "normal"
            TrackerListLevel.PRO -> "pro"
        },
        malicious = when (threatListLevel) {
            ThreatListLevel.MEDIUM -> "medium"
            ThreatListLevel.FULL -> "full"
        },
    ),
    enabledDnsFilterCategories(),
)
