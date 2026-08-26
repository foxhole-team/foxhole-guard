package com.foxhole.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ThreatIntelDocument(
    val schema: Int = SCHEMA,
    val packages: List<String> = emptyList(),
    val certs: List<String> = emptyList(),

    val certsSha1: List<String> = emptyList(),

    val domains: List<String> = emptyList(),
    /** Literal indicator addresses, IPv4 or IPv6, for destinations that never carry a name. */
    val ips: List<String> = emptyList(),

    val indicatorKinds: Map<String, String> = emptyMap(),
) {
    fun indicatorKind(indicator: String): ThreatIndicatorKind =
        ThreatIndicatorKind.fromWireName(indicatorKinds[indicator.trim().lowercase()])

    companion object {
        const val SCHEMA = 4

        const val MIN_SUPPORTED_SCHEMA = 1

        fun supportsSchema(schema: Int): Boolean = schema in MIN_SUPPORTED_SCHEMA..SCHEMA
    }
}

@Serializable
enum class ThreatIndicatorKind {
    COMMAND_AND_CONTROL,

    MALWARE_DISTRIBUTION,

    SHARED_INFRASTRUCTURE,

    UNCLASSIFIED,
    ;

    companion object {
        fun fromWireName(raw: String?): ThreatIndicatorKind {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) {
                return UNCLASSIFIED
            }
            return entries.firstOrNull { kind -> kind.name.equals(value, ignoreCase = true) } ?: UNCLASSIFIED
        }
    }
}
