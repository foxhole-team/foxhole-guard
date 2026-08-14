package com.foxhole.core.runtime

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.dnsRuleSetTag
import com.foxhole.core.model.enabledDnsFilterCategories

/**
 * Resolved on-device paths to the verified DNS filter rule sets, consumed by the runtime config
 * assembler (engine). Kept in its own engine file (not the host DnsFilterAssetInstaller) so the
 * engine does not depend on the host installer for this type.
 *
 * [categoryPaths] carries the per-category rule sets when a schema-2 filter release is installed;
 * with it present the per-category toggles gate their own rule sets and blocked-query logs carry
 * the category tag. Without it the legacy merged list at [adGuardDnsFilterPath] stays in effect.
 */
data class DnsFilterRuntimePaths(
    val adGuardDnsFilterPath: String,
    val foxCoreBootstrap: FoxCoreDnsRuleSetBootstrap? = null,
    val adGuardVpnCompatibilityDomains: List<String> = emptyList(),
    val categoryPaths: Map<DnsFilterCategory, String> = emptyMap(),
)

/**
 * The (tag, path) pairs of the DNS filter rule sets active for the current settings, in severity
 * order — an overlapping domain is attributed to the most severe category that matches first.
 * Empty when per-category rule sets are unavailable (legacy merged fallback applies instead).
 */
fun DnsSettings.activeDnsFilterCategoryRuleSets(paths: DnsFilterRuntimePaths?): List<Pair<String, String>> {
    if (paths == null || paths.categoryPaths.isEmpty()) {
        return emptyList()
    }
    val enabled = enabledDnsFilterCategories()
    return DnsFilterCategory.entries
        .filter { category -> category in enabled }
        .mapNotNull { category ->
            paths.categoryPaths[category]?.let { path -> category.dnsRuleSetTag to path }
        }
}
