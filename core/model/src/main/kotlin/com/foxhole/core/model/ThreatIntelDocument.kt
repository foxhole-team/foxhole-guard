package com.foxhole.core.model

import kotlinx.serialization.Serializable

/**
 * Wire format of a FoxHole Sentinel installed-app threat-intel bundle, shared by the bundled seed
 * asset and the signed remote feed. Pure data so both `:core:model` consumers and the security
 * core can read it; the security core converts it into its matching structure.
 *
 * `packages` are package names; `certs` are lower-cased hex SHA-256 signing-certificate hashes.
 * Both are advisory inputs to scoring — matching one raises an installed app to a known threat.
 */
@Serializable
data class ThreatIntelDocument(
    val schema: Int = SCHEMA,
    val packages: List<String> = emptyList(),
    val certs: List<String> = emptyList(),
    /**
     * Signing-certificate SHA-1 fingerprints.
     *
     * Schema 1 carried SHA-256 only, and every public stalkerware dataset publishes SHA-1
     * (androguard's `cert.sha1_fingerprint`). SHA-1 to SHA-256 is a second preimage rather than a
     * conversion, so a feed built from those sources could only ever populate `packages` — which
     * is the half an author changes for free, while the signing key is the half they cannot. This
     * band exists so the durable identifier is usable at all. Collisions are irrelevant here: this
     * is matching against a list of known-bad, not authentication.
     */
    val certsSha1: List<String> = emptyList(),
    /**
     * Network indicator hosts, lower-cased, without a scheme or port. Matched against the
     * destination of a live flow, so a match names the app that opened it — the one thing an
     * on-device scanner can say that a static list cannot. What each one IS comes from
     * [indicatorKinds]; the list itself says only "this destination is published".
     */
    val domains: List<String> = emptyList(),
    /** Literal indicator addresses, IPv4 or IPv6, for destinations that never carry a name. */
    val ips: List<String> = emptyList(),
    /**
     * Schema 4: what each entry of [domains] and [ips] actually is, keyed by the indicator itself.
     *
     * Without it every indicator scored the same, so a hit on a shared CDN address — which
     * thousands of ordinary apps talk to — produced the same confident HIGH finding as a hit on a
     * command-and-control endpoint. A feed that does not classify an indicator (a schema-3 feed, or
     * a newer one that lists a host without saying what it is) leaves it
     * [ThreatIndicatorKind.UNCLASSIFIED], which is deliberately not enough for a HIGH on its own.
     *
     * Kept as raw strings like [certs] and [domains]: the security core converts, and an unknown
     * kind from a future feed must degrade that one indicator rather than fail the whole document.
     */
    val indicatorKinds: Map<String, String> = emptyMap(),
) {
    /** The classification of one indicator; [ThreatIndicatorKind.UNCLASSIFIED] when unstated. */
    fun indicatorKind(indicator: String): ThreatIndicatorKind =
        ThreatIndicatorKind.fromWireName(indicatorKinds[indicator.trim().lowercase()])

    companion object {
        // 2: added certsSha1. 3: added domains/ips for the network matcher. 4: added
        // indicatorKinds, so severity can follow what an indicator is.
        const val SCHEMA = 4

        /**
         * Oldest wire schema this build still reads. Every field added since defaults to its
         * absent value, and every absent value is the quiet reading — so an older feed keeps
         * working and simply claims less, which is the whole point of adding fields additively.
         */
        const val MIN_SUPPORTED_SCHEMA = 1

        fun supportsSchema(schema: Int): Boolean = schema in MIN_SUPPORTED_SCHEMA..SCHEMA
    }
}

/**
 * What a published network indicator is, which is what decides how loud a hit on it may be.
 *
 * The three named kinds are the distinction the feed has to make; [UNCLASSIFIED] is what a feed
 * that did not make it degrades to, never a claim the feed made.
 */
@Serializable
enum class ThreatIndicatorKind {
    /**
     * An endpoint whose known purpose is controlling malware. Nothing benign talks there, so a hit
     * is the strongest statement this matcher can make.
     */
    COMMAND_AND_CONTROL,

    /**
     * A host that serves malicious payloads. Strong, but weaker than C2: distribution hosts are
     * routinely compromised legitimate sites, so a hit names a destination, not a controlled device.
     */
    MALWARE_DISTRIBUTION,

    /**
     * A CDN, hosting provider or other address a threat happens to share with everyone else. A hit
     * is worth recording and is never, by itself, evidence of anything about the app that made it.
     */
    SHARED_INFRASTRUCTURE,

    /**
     * The feed listed the indicator without saying what it is — every indicator in a schema-3 feed,
     * and any kind a future feed invents. Treated as "published, unknown": recorded and surfaced,
     * but never scored as a confident hit.
     */
    UNCLASSIFIED,
    ;

    companion object {
        /** Case-insensitive by enum name. Anything else, including null, is [UNCLASSIFIED]. */
        fun fromWireName(raw: String?): ThreatIndicatorKind {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) {
                return UNCLASSIFIED
            }
            return entries.firstOrNull { kind -> kind.name.equals(value, ignoreCase = true) } ?: UNCLASSIFIED
        }
    }
}
