package com.foxhole.guard.router

internal enum class RouterDiscoveryKind { INSTALLED, SSH_AVAILABLE, UNKNOWN, NOT_FOUND }

internal enum class RouterDiscoveryIssue { API_UNAVAILABLE, TIMEOUT, TLS, NETWORK, PACKAGE_STATUS }

internal data class RouterDiscoveryResult(
    val address: String,
    val kind: RouterDiscoveryKind,
    val pinConfigured: Boolean? = null,
    val resetSupported: Boolean = false,
    val discoveryId: String,
    val issue: RouterDiscoveryIssue? = null,
    val packageVersion: String? = null,
)

internal data class RouterPackageStatus(val pinConfigured: Boolean, val resetSupported: Boolean, val packageVersion: String? = null)

internal fun routerDiscoveryPackageVersion(value: String?): String? {
    if (value == null || value.length !in 1..64) return null
    return try { compareRouterPackageVersions(value, value); value }
    catch (_: RouterUpdateFailure) { null }
    catch (_: RouterApiFailure) { null }
}
