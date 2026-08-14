package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.ThreatIndicatorKind
import com.foxhole.core.model.ThreatIntelDocument

/** What a destination matched, so the finding can name the reason rather than a score. */
enum class NetworkIocKind { DOMAIN, IP }

data class NetworkIocHit(
    val kind: NetworkIocKind,
    /** The indicator that matched — for a subdomain this is the parent that is listed. */
    val indicator: String,
    /**
     * What the feed says that indicator IS, which is what the severity follows.
     *
     * Defaults to [ThreatIndicatorKind.UNCLASSIFIED] rather than to the worst case: a feed that
     * did not classify an indicator has not told us it is command-and-control, and a matcher that
     * assumed so is exactly how a shared CDN address became a confident HIGH.
     */
    val threatKind: ThreatIndicatorKind = ThreatIndicatorKind.UNCLASSIFIED,
)

/**
 * Matches a live flow's destination against the known-bad hosts of a threat-intel bundle.
 *
 * This is the one statement an on-device matcher can make that a static app list cannot: the flow
 * carries the package that opened it, so a hit names the app rather than the device.
 *
 * A hit means "this destination is on a published indicator list", never "this device is
 * compromised" — and the absence of a hit means nothing at all. Both upstream datasets say so
 * explicitly, and the wording shown to a user has to keep that distinction.
 */
class NetworkIocMatcher(document: ThreatIntelDocument) {

    // Indicator -> what the feed says it is. A map rather than a set because the classification is
    // the difference between "this app is being controlled" and "this app uses a CDN".
    private val domains: Map<String, ThreatIndicatorKind> = document.indexOf(document.domains, ::normalizeHost)
    private val ips: Map<String, ThreatIndicatorKind> =
        document.indexOf(document.ips) { raw -> raw.trim().lowercase().ifEmpty { null } }

    val isEmpty: Boolean get() = domains.isEmpty() && ips.isEmpty()

    /**
     * [remoteHost] is the destination as the flow reports it: a hostname when one was resolved,
     * otherwise a literal address.
     */
    fun match(remoteHost: String): NetworkIocHit? {
        val host = normalizeHost(remoteHost) ?: return null
        ips[host]?.let { threatKind ->
            return NetworkIocHit(NetworkIocKind.IP, host, threatKind)
        }
        // Walk the label suffixes so a listed `evil.com` also catches `cdn.evil.com`, while `evil.com`
        // never catches `notevil.com` — a substring match would.
        var candidate = host
        while (true) {
            domains[candidate]?.let { threatKind ->
                return NetworkIocHit(NetworkIocKind.DOMAIN, candidate, threatKind)
            }
            val dot = candidate.indexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(dot + 1)
            if (!candidate.contains('.')) return null
        }
    }

    private companion object {
        /**
         * Normalizes the listed indicators and pairs each with its kind. The lookup uses the
         * normalized form, so a feed that classified `EVIL.Example.` still classifies the match.
         */
        fun ThreatIntelDocument.indexOf(
            indicators: List<String>,
            normalize: (String) -> String?,
        ): Map<String, ThreatIndicatorKind> {
            val index = mutableMapOf<String, ThreatIndicatorKind>()
            indicators.forEach { raw ->
                val normalized = normalize(raw) ?: return@forEach
                val kind = indicatorKind(raw)
                // A duplicate listed under two kinds keeps the louder one: the feed did assert the
                // stronger claim somewhere, and dropping it would silence a real C2 entry because a
                // second list also mentioned the address.
                val existing = index[normalized]
                index[normalized] =
                    if (existing == null || kind.networkIocScore() > existing.networkIocScore()) kind else existing
            }
            return index
        }

        /** Feeds and flow records both arrive with stray case, ports, trailing dots and brackets. */
        fun normalizeHost(raw: String): String? {
            var value = raw.trim().lowercase().trim('.')
            if (value.startsWith("[")) {
                // IPv6 literal with a port: `[2001:db8::1]:443`.
                value = value.removePrefix("[").substringBefore("]")
            } else if (value.count { it == ':' } == 1) {
                value = value.substringBefore(':')
            }
            return value.ifEmpty { null }
        }
    }
}
