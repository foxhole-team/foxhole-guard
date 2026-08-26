package com.foxhole.core.runtime

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.dnsRuleSetTag
import com.foxhole.core.model.enabledDnsFilterCategories

data class DnsFilterRuntimePaths(
    val adGuardDnsFilterPath: String,
    val foxCoreBootstrap: FoxCoreDnsRuleSetBootstrap? = null,
    val adGuardVpnCompatibilityDomains: List<String> = emptyList(),
    val categoryPaths: Map<DnsFilterCategory, String> = emptyMap(),
)

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
