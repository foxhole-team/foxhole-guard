package com.foxhole.guard.runtime

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * FoxHole DB — the app's single data repository (github.com/foxhole-team/foxhole-db), published
 * through GitHub Pages. Four signed groups live there: the DNS ruleset, the Tor bridges mirror,
 * the SENTINEL threat intel and the geo database. Every group ships a manifest signed with the
 * one repository key below; the app downloads only the groups the user has enabled.
 */
internal const val FOXHOLE_DB_PAGES_BASE_URL = "https://foxhole-team.github.io/foxhole-db"

/**
 * The feed base actually in use: the repository the user configured, or the official one.
 *
 * All four manifests are named relative to this single base, so redirecting the repository moves
 * every data set together — a per-feed override would leave the app reading half its data from one
 * mirror and half from another, with no screen able to say which.
 *
 * The signature check does not move with it: [requireFoxholeDbManifestSignature] stays pinned to
 * the key below, so a mirror is only usable if it is published by the same tooling.
 */
internal fun foxholeDbBaseUrl(configuredBaseUrl: String): String =
    configuredBaseUrl.trim().ifEmpty { FOXHOLE_DB_PAGES_BASE_URL }.trimEnd('/')

internal fun foxholeDbManifestUrl(configuredBaseUrl: String): String =
    "${foxholeDbBaseUrl(configuredBaseUrl)}/manifest.json"

internal fun foxholeDbBridgesManifestUrl(configuredBaseUrl: String): String =
    "${foxholeDbBaseUrl(configuredBaseUrl)}/bridges-manifest.json"

internal fun foxholeDbGeoIpManifestUrl(configuredBaseUrl: String): String =
    "${foxholeDbBaseUrl(configuredBaseUrl)}/geoip-manifest.json"

internal fun foxholeDbThreatIntelManifestUrl(configuredBaseUrl: String): String =
    "${foxholeDbBaseUrl(configuredBaseUrl)}/threat-intel-manifest.json"

// The repository's manifest.public.pem, pinned. One key signs every feed manifest.
internal const val FOXHOLE_DB_MANIFEST_PUBLIC_KEY_PEM = """
-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUucWYOJ+RmNoGzlv6lyQ7TdvK1Op
6TRMy+ADsghRHXfKh8gIytQMTq0hKq7TB1GRmVeyysYX3kpmuGGz1buayA==
-----END PUBLIC KEY-----
"""

internal const val FOXHOLE_DB_MANIFEST_PUBLIC_KEY_SHA256 =
    "3acd123f1fd03f8aee97b2e71029ba1f6efdd9c17703419d7cda78a790198d69"

/**
 * Verifies an ECDSA-P256 manifest signature against the pinned FoxHole DB key.
 * Throws [IllegalStateException] on mismatch — callers treat that as non-retryable.
 */
internal fun requireFoxholeDbManifestSignature(
    manifestBytes: ByteArray,
    signatureBytes: ByteArray,
    publicKeyPem: String = FOXHOLE_DB_MANIFEST_PUBLIC_KEY_PEM,
) {
    val keyBytes =
        publicKeyPem
            .lineSequence()
            .map(String::trim)
            .filter { line -> line.isNotBlank() && !line.startsWith("-----") }
            .joinToString(separator = "")
            .let(Base64.getDecoder()::decode)
    val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
    val verifier =
        Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey)
            update(manifestBytes)
        }
    check(verifier.verify(signatureBytes)) { "manifest signature verification failed" }
}
