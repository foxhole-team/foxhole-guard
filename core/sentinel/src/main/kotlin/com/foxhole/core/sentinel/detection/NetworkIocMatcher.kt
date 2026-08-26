package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.ThreatIndicatorKind
import com.foxhole.core.model.ThreatIntelDocument

enum class NetworkIocKind { DOMAIN, IP }

data class NetworkIocHit(
    val kind: NetworkIocKind,

    val indicator: String,

    val threatKind: ThreatIndicatorKind = ThreatIndicatorKind.UNCLASSIFIED,
)

class NetworkIocMatcher(document: ThreatIntelDocument) {
    private val domains: Map<String, ThreatIndicatorKind> = document.indexOf(document.domains, ::normalizeHost)
    private val ips: Map<String, ThreatIndicatorKind> =
        document.indexOf(document.ips) { raw -> raw.trim().lowercase().ifEmpty { null } }

    val isEmpty: Boolean get() = domains.isEmpty() && ips.isEmpty()

    fun match(remoteHost: String): NetworkIocHit? {
        val host = normalizeHost(remoteHost) ?: return null
        ips[host]?.let { threatKind ->
            return NetworkIocHit(NetworkIocKind.IP, host, threatKind)
        }

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
        fun ThreatIntelDocument.indexOf(
            indicators: List<String>,
            normalize: (String) -> String?,
        ): Map<String, ThreatIndicatorKind> {
            val index = mutableMapOf<String, ThreatIndicatorKind>()
            indicators.forEach { raw ->
                val normalized = normalize(raw) ?: return@forEach
                val kind = indicatorKind(raw)

                val existing = index[normalized]
                index[normalized] =
                    if (existing == null || kind.networkIocScore() > existing.networkIocScore()) kind else existing
            }
            return index
        }

        fun normalizeHost(raw: String): String? {
            var value = raw.trim().lowercase().trim('.')
            if (value.startsWith("[")) {
                value = value.removePrefix("[").substringBefore("]")
            } else if (value.count { it == ':' } == 1) {
                value = value.substringBefore(':')
            }
            return value.ifEmpty { null }
        }
    }
}
