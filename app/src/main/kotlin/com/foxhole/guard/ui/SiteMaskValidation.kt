package com.foxhole.guard.ui

import com.foxhole.guard.R

internal fun normalizedSiteMaskToken(value: String): String? = normalizeSiteAddressMask(value)

internal fun unsupportedSiteMask(value: String): Boolean = value.trim().let {
    it.startsWith("kw:", ignoreCase = true) || it.startsWith("re:", ignoreCase = true)
}

internal fun siteMaskValidationErrorRes(value: String): Int? = when {
    value.isBlank() -> R.string.site_mask_required
    unsupportedSiteMask(value) -> R.string.site_mask_unsupported
    normalizedSiteMaskToken(value) == null -> R.string.site_exception_invalid_error
    else -> null
}

internal data class SiteRuleMaskParts(val tokens: List<String>, val domains: List<String>, val cidrs: List<String>)

internal fun siteRuleMaskParts(values: List<String>): SiteRuleMaskParts {
    require(values.isNotEmpty() && values.size <= 4_096)
    val tokens = values.map { requireNotNull(normalizedSiteMaskToken(it)) }.distinct()
    val domains = mutableListOf<String>()
    val cidrs = mutableListOf<String>()
    tokens.forEach { token ->
        when {
            token.startsWith("range:") -> {
                val cover = siteAddressRangeCidrs(token)
                require(cover.size <= 254 && cidrs.size + cover.size <= 4_096)
                cidrs.addAll(cover)
            }
            token.startsWith("cidr:") -> { require(cidrs.size < 4_096); cidrs.add(token.removePrefix("cidr:")) }
            else -> domains.add(token)
        }
    }
    return SiteRuleMaskParts(tokens, domains, cidrs.distinct())
}
