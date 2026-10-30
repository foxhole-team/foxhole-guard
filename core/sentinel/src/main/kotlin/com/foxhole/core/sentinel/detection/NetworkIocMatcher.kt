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
        document.indexOf(document.ips, ::normalizeIpLiteral)

    val isEmpty: Boolean get() = domains.isEmpty() && ips.isEmpty()

    fun match(remoteHost: String): NetworkIocHit? {
        val host = normalizeHost(remoteHost) ?: return null
        normalizeIpLiteral(host)?.let { address ->
            ips[address]?.let { threatKind ->
                return NetworkIocHit(NetworkIocKind.IP, address, threatKind)
            }
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
            val value = raw.trim().lowercase().trim('.')
            val host = when {
                value.startsWith("[") -> bracketedHost(value)
                value.count { it == ':' } == 1 -> hostWithPort(value)
                else -> value
            } ?: return null
            if ('[' in host || ']' in host || host.any(Char::isWhitespace)) return null
            return host.ifEmpty { null }
        }

        fun bracketedHost(value: String): String? {
            val closing = value.indexOf(']')
            if (closing < 0) return null
            val suffix = value.substring(closing + 1)
            if (!validPortSuffix(suffix)) return null
            val host = value.substring(1, closing)
            return host.takeIf { normalizeIpLiteral(it) != null }
        }

        fun validPortSuffix(value: String): Boolean =
            value.isEmpty() || (value.startsWith(":") && validPort(value.drop(1)))

        fun hostWithPort(value: String): String? =
            if (validPort(value.substringAfter(':'))) {
                value.substringBefore(':')
            } else {
                null
            }

        fun validPort(value: String): Boolean =
            value.isNotEmpty() && value.all { it in '0'..'9' } && value.toIntOrNull()?.let { it in 1..65535 } == true
    }
}
