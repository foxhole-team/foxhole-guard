package com.foxhole.guard.router

internal enum class RouterDiscoveryKind { INSTALLED, SSH_AVAILABLE, UNKNOWN, NOT_FOUND }

internal data class RouterDiscoveryResult(
    val address: String,
    val kind: RouterDiscoveryKind,
    val pinConfigured: Boolean? = null,
    val resetSupported: Boolean = false,
    val discoveryId: String,
)

internal data class RouterPackageStatus(val pinConfigured: Boolean, val resetSupported: Boolean)
