package com.foxhole.core.model

import kotlinx.serialization.Serializable

/**
 * Wire format of a FOXHOLE SENTINEL installed-app threat-intel bundle, shared by the bundled seed
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
) {
    companion object {
        // 2: added certsSha1. No feed is hosted yet, so nothing in the wild carries schema 1.
        const val SCHEMA = 2
    }
}
