package com.foxhole.guard.runtime

internal sealed interface DnsFilterUpdateBackend {
    data object Legacy : DnsFilterUpdateBackend

    class Categorized(
        val store: CategorizedDnsStagedStore,
        val activate: suspend (CategorizedDnsStage, VerifiedCategorizedDnsRuleSet) -> CategorizedDnsNativeActivation,
    ) : DnsFilterUpdateBackend
}
