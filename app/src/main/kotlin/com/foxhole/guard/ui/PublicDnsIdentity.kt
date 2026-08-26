package com.foxhole.guard.ui

import androidx.compose.runtime.Immutable

@Immutable
data class PublicDnsIdentity(
    val serverAddress: String? = null,
    val countryCode: String? = null,
    val phase: PublicDnsIdentityPhase = PublicDnsIdentityPhase.IDLE,
)

enum class PublicDnsIdentityPhase {
    IDLE,
    LOADING,
    RESOLVED,
    FAILED,
}
